package net.rkunze.maven.compiler.jsr308javac;

/**
 * The MIT License
 *
 * Copyright (c) 2005, The Codehaus
 * Copyright (c) 2014, Richard Kunze
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 *
 * Copyright 2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import org.codehaus.plexus.compiler.AbstractCompiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.util.StringUtils;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @author <a href="mailto:matthew.pocock@ncl.ac.uk">Matthew Pocock</a>
 * @author <a href="mailto:joerg.wassmer@web.de">J&ouml;rg Wa&szlig;mer</a>
 * @author Others
 * @plexus.component role="org.codehaus.plexus.compiler.Compiler"
 * role-hint="javac+jsr308"
 */
public class JavacJSR308Compiler
    extends AbstractCompiler
{

    // see compiler.warn.warning in compiler.properties of javac sources
    private static final String[] WARNING_PREFIXES = { "warning: ", "\u8b66\u544a: ", "\u8b66\u544a\uff1a " };

    // see compiler.note.note in compiler.properties of javac sources
    private static final String[] NOTE_PREFIXES = { "Note: ", "\u6ce8: ", "\u6ce8\u610f\uff1a " };

    private static final Object LOCK = new Object();

    private static final String JAVAC_CLASSNAME = "com.sun.tools.javac.Main";
    private static volatile Class<?> JAVAC_CLASS;

    private List<Class<?>> javaccClasses = new CopyOnWriteArrayList<Class<?>>();

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public JavacJSR308Compiler()
    {
        super( CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE, ".java", ".class", null );
    }
    
    // ----------------------------------------------------------------------
    // Compiler Implementation
    // ----------------------------------------------------------------------

    public CompilerResult performCompile( CompilerConfiguration config )
        throws CompilerException
    {
        if ( config.isFork() )
        {
            throw new CompilerException("'fork' not supported.");
        }
        
        File destinationDir = new File( config.getOutputLocation() );

        if ( !destinationDir.exists() )
        {
            destinationDir.mkdirs();
        }

        String[] sourceFiles = getSourceFiles( config );

        if ( ( sourceFiles == null ) || ( sourceFiles.length == 0 ) )
        {
            return new CompilerResult();
        }

        if ( ( getLogger() != null ) && getLogger().isInfoEnabled() )
        {
            getLogger().info( "Compiling " + sourceFiles.length + " " +
                                  "source file" + ( sourceFiles.length == 1 ? "" : "s" ) +
                                  " to " + destinationDir.getAbsolutePath() );
        }

        String[] args = buildCompilerArguments( config, sourceFiles );

        CompilerResult result;

        result = compileInProcess( args, config );

        return result;
    }

    public String[] createCommandLine( CompilerConfiguration config )
        throws CompilerException
    {
        return buildCompilerArguments( config, getSourceFiles( config ) );
    }

    public static String[] buildCompilerArguments( CompilerConfiguration config, String[] sourceFiles ) throws CompilerException
    {
        if (!ClasspathConfig.getCheckerJar().exists()) {
            throw new CompilerException("Type checker jar not found: " + ClasspathConfig.getCheckerJar().getAbsolutePath());
        }
        if (!ClasspathConfig.getAnnotatedJDK(System.getProperty("java.version")).exists()) {
            throw new CompilerException("Annotaded JDK jar not found: " + ClasspathConfig.getAnnotatedJDK(System.getProperty("java.version")).getAbsolutePath());
        }
        List<String> args = new ArrayList<String>();

        // ----------------------------------------------------------------------
        // Set output
        // ----------------------------------------------------------------------

        File destinationDir = new File( config.getOutputLocation() );

        args.add( "-d" );

        args.add( destinationDir.getAbsolutePath() );

        // ----------------------------------------------------------------------
        // Set the class and source paths
        // ----------------------------------------------------------------------
        
        // FIXME: Handle "bootclasspath" argument
        args.add( "-Xbootclasspath/p:" + getPathString(Arrays.asList(
                    ClasspathConfig.getAnnotatedJDK(System.getProperty("java.version")).getAbsolutePath(),
                    ClasspathConfig.getCompilerJar().getAbsolutePath()
                )));

        args.add( "-classpath" );
        List<String> originalClasspath = config.getClasspathEntries();
        List<String> classpathEntries = new ArrayList<>(originalClasspath==null ? 1 : originalClasspath.size() + 1);
        classpathEntries.add(ClasspathConfig.getCheckerJar().getAbsolutePath());
        if ( originalClasspath != null && !originalClasspath.isEmpty() )
        {
            classpathEntries.addAll(originalClasspath);
        }
        args.add( getPathString( classpathEntries ) );

        List<String> sourceLocations = config.getSourceLocations();
        if ( sourceLocations != null && !sourceLocations.isEmpty() )
        {
            //always pass source path, even if sourceFiles are declared,
            //needed for jsr269 annotation processing, see MCOMPILER-98
            args.add( "-sourcepath" );

            args.add( getPathString( sourceLocations ) );
        }
        args.addAll( Arrays.asList( sourceFiles ) );

        if ( !isPreJava16( config ) )
        {
            //now add jdk 1.6 annotation processing related parameters

            if ( config.getGeneratedSourcesDirectory() != null )
            {
                config.getGeneratedSourcesDirectory().mkdirs();

                args.add( "-s" );
                args.add( config.getGeneratedSourcesDirectory().getAbsolutePath() );
            }
            if ( config.getProc() != null )
            {
                args.add( "-proc:" + config.getProc() );
            }
            if ( config.getAnnotationProcessors() != null )
            {
                args.add( "-processor" );
                String[] procs = config.getAnnotationProcessors();
                StringBuilder buffer = new StringBuilder();
                for ( int i = 0; i < procs.length; i++ )
                {
                    if ( i > 0 )
                    {
                        buffer.append( "," );
                    }

                    buffer.append( procs[i] );
                }
                args.add( buffer.toString() );
            }
        }

        if ( config.isOptimize() )
        {
            args.add( "-O" );
        }

        if ( config.isDebug() )
        {
            if ( StringUtils.isNotEmpty( config.getDebugLevel() ) )
            {
                args.add( "-g:" + config.getDebugLevel() );
            }
            else
            {
                args.add( "-g" );
            }
        }

        if ( config.isVerbose() )
        {
            args.add( "-verbose" );
        }

        if ( config.isShowDeprecation() )
        {
            args.add( "-deprecation" );

            // This is required to actually display the deprecation messages
            config.setShowWarnings( true );
        }

        if ( !config.isShowWarnings() )
        {
            args.add( "-nowarn" );
        }

        // TODO: this could be much improved
        if ( StringUtils.isEmpty( config.getTargetVersion() ) )
        {
            // Required, or it defaults to the target of your JDK (eg 1.5)
            args.add( "-target" );
            args.add( "1.1" );
        }
        else
        {
            args.add( "-target" );
            args.add( config.getTargetVersion() );
        }

        if ( !suppressSource( config ) && StringUtils.isEmpty( config.getSourceVersion() ) )
        {
            // If omitted, later JDKs complain about a 1.1 target
            args.add( "-source" );
            args.add( "1.3" );
        }
        else if ( !suppressSource( config ) )
        {
            args.add( "-source" );
            args.add( config.getSourceVersion() );
        }

        if ( !suppressEncoding( config ) && !StringUtils.isEmpty( config.getSourceEncoding() ) )
        {
            args.add( "-encoding" );
            args.add( config.getSourceEncoding() );
        }

        for ( Map.Entry<String, String> entry : config.getCustomCompilerArgumentsAsMap().entrySet() )
        {
            String key = entry.getKey();

            if ( StringUtils.isEmpty( key ) || key.startsWith( "-J" ) )
            {
                continue;
            }

            args.add( key );

            String value = entry.getValue();

            if ( StringUtils.isEmpty( value ) )
            {
                continue;
            }

            args.add( value );
        }

        return args.toArray( new String[args.size()] );
    }

    /**
     * Determine if the compiler is a version prior to 1.4.
     * This is needed as 1.3 and earlier did not support -source or -encoding parameters
     *
     * @param config The compiler configuration to test.
     * @return true if the compiler configuration represents a Java 1.4 compiler or later, false otherwise
     */
    private static boolean isPreJava14( CompilerConfiguration config )
    {
        String v = config.getCompilerVersion();

        if ( v == null )
        {
            return false;
        }

        return v.startsWith( "1.3" ) || v.startsWith( "1.2" ) || v.startsWith( "1.1" ) || v.startsWith( "1.0" );
    }

    /**
     * Determine if the compiler is a version prior to 1.6.
     * This is needed for annotation processing parameters.
     *
     * @param config The compiler configuration to test.
     * @return true if the compiler configuration represents a Java 1.6 compiler or later, false otherwise
     */
    private static boolean isPreJava16( CompilerConfiguration config )
    {
        String v = config.getCompilerVersion();

        if ( v == null )
        {
            //mkleint: i haven't completely understood the reason for the
            //compiler version parameter, checking source as well, as most projects will have this one set, not the compiler
            String s = config.getSourceVersion();
            if ( s == null )
            {
                //now return true, as the 1.6 version is not the default - 1.4 is.
                return true;
            }
            return s.startsWith( "1.5" ) || s.startsWith( "1.4" ) || s.startsWith( "1.3" ) || s.startsWith( "1.2" )
                || s.startsWith( "1.1" ) || s.startsWith( "1.0" );
        }

        return v.startsWith( "1.5" ) || v.startsWith( "1.4" ) || v.startsWith( "1.3" ) || v.startsWith( "1.2" )
            || v.startsWith( "1.1" ) || v.startsWith( "1.0" );
    }


    private static boolean suppressSource( CompilerConfiguration config )
    {
        return isPreJava14( config );
    }

    private static boolean suppressEncoding( CompilerConfiguration config )
    {
        return isPreJava14( config );
    }

    /**
     * Compile the java sources in the current JVM, without calling an external executable,
     * using <code>com.sun.tools.javac.Main</code> class
     *
     * @param args   arguments for the compiler as they would be used in the command line javac
     * @param config compiler configuration
     * @return a CompilerResult object encapsulating the result of the compilation and any compiler messages
     * @throws CompilerException
     */
    CompilerResult compileInProcess( String[] args, CompilerConfiguration config )
        throws CompilerException
    {
        final Class<?> javacClass = getJavacClass( config );
        final Thread thread = Thread.currentThread();
        final ClassLoader contextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader( javacClass.getClassLoader() );
        getLogger().debug( "ttcl changed run compileInProcessWithProperClassloader" );
        try
        {
            return compileInProcessWithProperClassloader(javacClass, args);
        }
        finally
        {
            releaseJavaccClass( javacClass, config );
            thread.setContextClassLoader( contextClassLoader );
        }
    }

    protected CompilerResult compileInProcessWithProperClassloader( Class<?> javacClass, String[] args )
        throws CompilerException {
      return compileInProcess0(javacClass, args);
    }

    /**
     * Helper method for compileInProcess()
     */
    private static CompilerResult compileInProcess0( Class<?> javacClass, String[] args )
        throws CompilerException
    {
        StringWriter out = new StringWriter();

        Integer ok;

        List<CompilerMessage> messages;

        try
        {
            Method compile = javacClass.getMethod( "compile", new Class[]{ String[].class, PrintWriter.class } );

            ok = (Integer) compile.invoke( null, new Object[]{ args, new PrintWriter( out ) } );

            messages = parseModernStream( ok.intValue(), new BufferedReader( new StringReader( out.toString() ) ) );
        }
        catch ( NoSuchMethodException e )
        {
            throw new CompilerException( "Error while executing the compiler.", e );
        }
        catch ( IllegalAccessException e )
        {
            throw new CompilerException( "Error while executing the compiler.", e );
        }
        catch ( InvocationTargetException e )
        {
            throw new CompilerException( "Error while executing the compiler.", e );
        }
        catch ( IOException e )
        {
            throw new CompilerException( "Error while executing the compiler.", e );
        }

        boolean success = ok.intValue() == 0;
        return new CompilerResult( success, messages );
    }

    /**
     * Parse the output from the compiler into a list of CompilerMessage objects
     *
     * @param exitCode The exit code of javac.
     * @param input    The output of the compiler
     * @return List of CompilerMessage objects
     * @throws IOException
     */
    static List<CompilerMessage> parseModernStream( int exitCode, BufferedReader input )
        throws IOException
    {
        List<CompilerMessage> errors = new ArrayList<CompilerMessage>();

        String line;

        StringBuilder buffer;

        while ( true )
        {
            // cleanup the buffer
            buffer = new StringBuilder(); // this is quicker than clearing it

            // most errors terminate with the '^' char
            do
            {
                line = input.readLine();

                if ( line == null )
                {
                    // javac output not detected by other parsing
                    if ( buffer.length() > 0 && buffer.toString().startsWith( "javac:" ) )
                    {
                        errors.add( new CompilerMessage( buffer.toString(), CompilerMessage.Kind.ERROR ) );
                    }
                    return errors;
                }

                // TODO: there should be a better way to parse these
                if ( ( buffer.length() == 0 ) && line.startsWith( "error: " ) )
                {
                    errors.add( new CompilerMessage( line, true ) );
                }
                else if ( ( buffer.length() == 0 ) && isNote( line ) )
                {
                    // skip, JDK 1.5 telling us deprecated APIs are used but -Xlint:deprecation isn't set
                }
                else
                {
                    buffer.append( line );

                    buffer.append( EOL );
                }
            }
            while ( !line.endsWith( "^" ) );

            // add the error bean
            errors.add( parseModernError( exitCode, buffer.toString() ) );
        }
    }

    private static boolean isNote( String line )
    {
        for ( int i = 0; i < NOTE_PREFIXES.length; i++ )
        {
            if ( line.startsWith( NOTE_PREFIXES[i] ) )
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Construct a CompilerMessage object from a line of the compiler output
     *
     * @param exitCode The exit code from javac.
     * @param error    output line from the compiler
     * @return the CompilerMessage object
     */
    static CompilerMessage parseModernError( int exitCode, String error )
    {
        StringTokenizer tokens = new StringTokenizer( error, ":" );

        boolean isError = exitCode != 0;

        StringBuilder msgBuffer;

        try
        {
            // With Java 6 error output lines from the compiler got longer. For backward compatibility
            // .. and the time being, we eat up all (if any) tokens up to the erroneous file and source
            // .. line indicator tokens.

            boolean tokenIsAnInteger;

            String file = null;

            String currentToken = null;

            do
            {
                if ( currentToken != null )
                {
                    if ( file == null )
                    {
                        file = currentToken;
                    }
                    else
                    {
                        file = file + ':' + currentToken;
                    }
                }

                currentToken = tokens.nextToken();

                // Probably the only backward compatible means of checking if a string is an integer.

                tokenIsAnInteger = true;

                try
                {
                    Integer.parseInt( currentToken );
                }
                catch ( NumberFormatException e )
                {
                    tokenIsAnInteger = false;
                }
            }
            while ( !tokenIsAnInteger );

            String lineIndicator = currentToken;

            int startOfFileName = file.lastIndexOf( ']' );

            if ( startOfFileName > -1 )
            {
                file = file.substring( startOfFileName + 1 + EOL.length() );
            }

            int line = Integer.parseInt( lineIndicator );

            msgBuffer = new StringBuilder();

            String msg = tokens.nextToken( EOL ).substring( 2 );

            // Remove the 'warning: ' prefix
            String warnPrefix = getWarnPrefix( msg );
            if ( warnPrefix != null )
            {
                isError = false;
                msg = msg.substring( warnPrefix.length() );
            }
            else
            {
                isError = exitCode != 0;
            }

            msgBuffer.append( msg );

            msgBuffer.append( EOL );

            String context = tokens.nextToken( EOL );

            String pointer = tokens.nextToken( EOL );

            if ( tokens.hasMoreTokens() )
            {
                msgBuffer.append( context );    // 'symbol' line

                msgBuffer.append( EOL );

                msgBuffer.append( pointer );    // 'location' line

                msgBuffer.append( EOL );

                context = tokens.nextToken( EOL );

                try
                {
                    pointer = tokens.nextToken( EOL );
                }
                catch ( NoSuchElementException e )
                {
                    pointer = context;

                    context = null;
                }

            }

            String message = msgBuffer.toString();

            int startcolumn = pointer.indexOf( "^" );

            int endcolumn = context == null ? startcolumn : context.indexOf( " ", startcolumn );

            if ( endcolumn == -1 )
            {
                endcolumn = context.length();
            }

            return new CompilerMessage( file, isError, line, startcolumn, line, endcolumn, message.trim() );
        }
        catch ( NoSuchElementException e )
        {
            return new CompilerMessage( "no more tokens - could not parse error message: " + error, isError );
        }
        catch ( NumberFormatException e )
        {
            return new CompilerMessage( "could not parse error message: " + error, isError );
        }
        catch ( Exception e )
        {
            return new CompilerMessage( "could not parse error message: " + error, isError );
        }
    }

    private static String getWarnPrefix( String msg )
    {
        for ( int i = 0; i < WARNING_PREFIXES.length; i++ )
        {
            if ( msg.startsWith( WARNING_PREFIXES[i] ) )
            {
                return WARNING_PREFIXES[i];
            }
        }
        return null;
    }

    private void releaseJavaccClass( Class<?> javaccClass, CompilerConfiguration compilerConfiguration )
    {
        if ( compilerConfiguration.getCompilerReuseStrategy()
            == CompilerConfiguration.CompilerReuseStrategy.ReuseCreated )
        {
            javaccClasses.add( javaccClass );
        }

    }

    /**
     * Find the main class of JavaC. Return the same class for subsequent calls.
     *
     * @return the non-null class.
     * @throws CompilerException if the class has not been found.
     */
    private Class<?> getJavacClass( CompilerConfiguration compilerConfiguration )
        throws CompilerException
    {
        Class<?> c = null;
        switch ( compilerConfiguration.getCompilerReuseStrategy() )
        {
            case AlwaysNew:
                return createJavacClass();
            case ReuseCreated:
                synchronized ( javaccClasses )
                {
                    if ( javaccClasses.size() > 0 )
                    {
                        c = javaccClasses.get( 0 );
                        javaccClasses.remove( c );
                        return c;
                    }
                }
                c = createJavacClass();
                return c;
            case ReuseSame:
            default:
                c = JavacJSR308Compiler.JAVAC_CLASS;
                if ( c != null )
                {
                    return c;
                }
                synchronized ( JavacJSR308Compiler.LOCK )
                {
                    if ( c == null )
                    {
                        JavacJSR308Compiler.JAVAC_CLASS = c = createJavacClass();
                    }
                    return c;
                }


        }
    }


    /**
     * Helper method for create Javac class
     */
    protected Class<?> createJavacClass()
        throws CompilerException
    {
        try
        {
            if (!ClasspathConfig.getCompilerJar().exists()) {
                throw new CompilerException("Javac jar file not found: " + ClasspathConfig.getCompilerJar().getAbsolutePath());
            }

            ClassLoader javacClassLoader = new DelegateLastClassLoader(
                    ((URLClassLoader)getClass().getClassLoader()).getURLs() );

            final Thread thread = Thread.currentThread();
            final ClassLoader contextClassLoader = thread.getContextClassLoader();
            thread.setContextClassLoader( javacClassLoader );
            try
            {
                return javacClassLoader.loadClass( JavacJSR308Compiler.JAVAC_CLASSNAME );
            }
            finally
            {
                thread.setContextClassLoader( contextClassLoader );
            }
        }
        catch ( ClassNotFoundException ex )
        {
            throw new CompilerException( "Unable to locate the javac compiler in " 
                    + ClasspathConfig.getCompilerJar().getAbsolutePath(), ex );
        }
    }

}
