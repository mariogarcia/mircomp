/*
 * Copyright (C) 2016-2017 Mirco Colletta
 *
 * This file is part of MirComp.
 *
 * MirComp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MirComp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MirComp.  If not, see <http://www.gnu.org/licenses/>.
*/

/**
 * @author Mirco Colletta
 */

package io.github.mcolletta.miride

import java.util.Map

import static java.util.Collections.singletonList
import static java.util.Collections.singletonMap

import java.nio.file.attribute.*
import java.nio.file.*

import java.util.regex.Pattern
import java.util.regex.Matcher

import groovy.transform.CompileStatic

import groovy.util.GroovyScriptEngine
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.CompilerConfiguration

import io.github.mcolletta.mirchord.core.*
import static io.github.mcolletta.mirchord.core.Utils.*
import io.github.mcolletta.mirchord.interpreter.GroovyScriptImportType
import io.github.mcolletta.mirconverter.ZongConverter
import io.github.mcolletta.mirchord.core.Score as MirScore
import io.github.mcolletta.mirchord.interpreter.MirChordInterpreter

import com.xenoage.zong.core.Score


@CompileStatic
class ProjectInterpreter {

    boolean PRINT_STACKTRACE = false

    boolean staticCompile = false
    CompilerConfiguration configuration
    GroovyClassLoader engineClassLoader
    GroovyScriptEngine engine
    GroovyShell shell
    Map<String,GroovyScriptImportType> imports = [:]
    Path projectPath
    List<URL> roots = []
    Binding binding = new Binding()

    String includeRegex = /\(include\s+\"(.*?)\"\s*\)/
    Pattern includePattern = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE)


    ProjectInterpreter(String strPrjPath=null, boolean typecheck=false) {
        if (strPrjPath != null)
            projectPath = Paths.get(strPrjPath)
        staticCompile = typecheck
        createEngine()
    }

    void createConfiguration(Map<String,GroovyScriptImportType> imports_parm=[:]) {
        this.imports = imports_parm
        configuration = null
        def importCustomizer = new ImportCustomizer()
        setDefaultImports(importCustomizer)
        if (imports.size() > 0)
            addImports(importCustomizer)
        configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(importCustomizer)
        if (staticCompile) {
            //configuration.addCompilationCustomizers(new ASTTransformationCustomizer(CompileStatic.class))
            ASTTransformationCustomizer astcustomizer = new ASTTransformationCustomizer(
                singletonMap("extensions", singletonList("io.github.mcolletta.miride.InterpreterTypeCheckingExtension")),
                CompileStatic.class)
            configuration.addCompilationCustomizers(astcustomizer)
        }
    }

    void createEngine() {
        createConfiguration()
        engineClassLoader = null
        engine = null
        shell = null
        roots = []
        if (projectPath != null) {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException
                {
                    roots << dir.toUri().toURL()
                    return FileVisitResult.CONTINUE
                }
            })
            engine = new GroovyScriptEngine(roots as URL[], this.class.classLoader)
            engineClassLoader = engine.getGroovyClassLoader()
            shell = new GroovyShell(engineClassLoader, configuration)
        } else {
            shell = new GroovyShell(configuration)
        }
    }

    void setDefaultImports(ImportCustomizer importCustomizer) {
    	importCustomizer.addImports 'io.github.mcolletta.mirchord.interpreter.MirChord'
        importCustomizer.addStarImports 'io.github.mcolletta.mirchord.core'
        importCustomizer.addStaticStars 'io.github.mcolletta.mirchord.core.Utils'
        importCustomizer.addStaticStars 'io.github.mcolletta.mirconverter.Helper'
        importCustomizer.addImports 'com.xenoage.utils.math.Fraction'
    	importCustomizer.addStaticStars 'com.xenoage.utils.math.Fraction'
    }

    void addImports(ImportCustomizer importCustomizer) {
    	for(Map.Entry<String,GroovyScriptImportType> e : imports.entrySet()) {
            String k = e.getKey()
            GroovyScriptImportType itype = e.getValue()
            switch(itype) {
            	case GroovyScriptImportType.IMPORTS:
            		importCustomizer.addImports(k)
            		break
            	case GroovyScriptImportType.IMPORTS_STAR:
            		importCustomizer.addStarImports(k)
            		break
            	case GroovyScriptImportType.IMPORT_STATIC_STARS:
            		importCustomizer.addStaticStars(k)
            		break
            	default:
            		break
            }
        }
    }

    void addLib(File lib) {
        if (engineClassLoader != null) {
            // engineClassLoader.addClasspath(lib.getPath())
            Files.walkFileTree(lib.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attr) {
                    if (path.getFileName().toString().endsWith(".jar")) {
                        engineClassLoader.addURL(path.toFile().toURL())
                        // shell.getClassLoader().addURL(path.toFile().toURL())
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    Script getScript(String source, String scriptName=null) {
        Script script = null
        if (scriptName != null)
            script = shell.parse(source, scriptName)
        else
            script = shell.parse(source)
        return script
    }

    Object executeScript(String strPath) {
        Path scriptPath = Paths.get(strPath)
        return executeScript(scriptPath)
    }

    Object executeScript(Path scriptPath) {
        // more than one file in the folder tree may have same name
        // String fileName = scriptPath.getFileName().toString()
        // return engine.run(fileName, binding)
        String source = scriptPath.toFile().getText()
        executeScriptSource(source, scriptPath.toString())
    }

    Object executeScriptSource(String code, String scriptName) {
        Script script = getScript(code, scriptName)
        if (binding != null)
            script.setBinding(binding)
        try {
            def result = script.run()
            return result
        } catch(Exception ex) {
            System.err.println("Interpreter ERROR: " + ex.getMessage())
            if (PRINT_STACKTRACE) {
                StringWriter stacktrace = new StringWriter()
                ex.printStackTrace(new PrintWriter(stacktrace))
                System.err.println(stacktrace.toString())
            }
            return new InterpreterException(ex.getMessage())
        }
    }

    public Score createScore(String source, Path codePath=null) {
        // INCLUDES
        Matcher matcher = includePattern.matcher(source)
        List<String> includePaths = []
        while(matcher.find()) {
            includePaths.add(matcher.group(1))
        }
        List addon = []
        if (includePaths.size() > 0) {
            source = source.replaceAll(includeRegex, "")            
            if (codePath == null) {
                codePath = Paths.get(System.getProperty("user.home"))
                println "WARNING: code path not given, using " + codePath
            }
            for (String includePath : includePaths) {
                Path scriptPath = codePath.resolve(includePath)
                String scriptCode = (scriptPath != null) ? scriptPath.toFile().getText() : null
                String scriptName = (scriptPath != null) ? scriptPath.toString() : null
                if (scriptCode != null) {
                    Script script = getScript(scriptCode,scriptName)
                    addon.add(script)
                }
            }
        }
        MirChordInterpreter interpreter = new MirChordInterpreter(addon)
        try {
            MirScore mirscore = interpreter.evaluate(source)
            ZongConverter zconverter = new ZongConverter()
            Score score = zconverter.convert(mirscore)
            return score
        } catch(Exception ex) {
            System.err.println("Interpreter ERROR: " + ex.getMessage())
            if (PRINT_STACKTRACE) {
                StringWriter stacktrace = new StringWriter()
                ex.printStackTrace(new PrintWriter(stacktrace))
                System.err.println(stacktrace.toString())
            }
        }
        return null
    }
}

public class InterpreterException extends Exception {
    public InterpreterException(String message) {
        super(message);
    }
}
