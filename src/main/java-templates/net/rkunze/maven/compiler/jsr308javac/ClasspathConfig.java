/*
 * The MIT License
 * 
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

/*
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
 * 
 */
package net.rkunze.maven.compiler.jsr308javac;

import java.io.File;
import org.codehaus.plexus.compiler.CompilerException;

public class ClasspathConfig {

    private static File repositoryPath(String artifact) {
        String basedir = System.getProperty("localRepository");
        if (basedir == null) {
            basedir = System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository";
        }
        return new File(basedir + File.separator + artifact.replace("/", File.separator));
    }
    
    static File getCompilerJar() { return repositoryPath("${org.checkerframework:compiler:jar.relative.repository}"); }
    static File getCheckerJar() { return repositoryPath("${org.checkerframework:checker:jar.relative.repository}"); }
    static File getAnnotatedJDK(String jdkVersion) throws CompilerException {
        if (jdkVersion.startsWith("1.7.")) {
            return repositoryPath("${org.checkerframework:jdk7:jar.relative.repository}");
        } else if (jdkVersion.startsWith("1.8.")) {
            return repositoryPath("${org.checkerframework:jdk8:jar.relative.repository}");
        } else {
            throw new CompilerException("No annotated JDK jar found for java version " + jdkVersion);
        }
    }
}
