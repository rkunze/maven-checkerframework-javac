Maven support for the checker framework
============================

This project provides a simple Maven integration for the JSR308-enabled javac compiler from http://types.cs.washington.edu/checker-framework/

Usage
----------------------------

* Clone and build maven-checkerframework-javac
* Configure the maven compiler plugin to use the "javac+jsr308" compiler provided by maven-checkerframework-javac
* Enable the desired annotation processors from the checker framework

Exampke POM Snippet
----------------------------

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.1</version>
    <configuration>
        <compilerId>javac+jsr308</compilerId>
        <annotationProcessors>
            <processor>org.checkerframework.checker.nullness.NullnessChecker</processor>
            <processor>org.checkerframework.checker.interning.InterningChecker</processor>                        
        </annotationProcessors>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>net.rkunze.maven</groupId>
            <artifactId>maven-checkerframework-javac</artifactId>
            <version>1.8.4-1</version>
        </dependency>
    </dependencies>
</plugin>
