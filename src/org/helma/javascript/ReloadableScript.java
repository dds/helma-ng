/*
 *  Copyright 2004 Hannes Wallnoefer <hannes@helma.at>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.helma.javascript;

import org.helma.repository.Repository;
import org.helma.repository.Resource;
import org.helma.repository.Trackable;
import org.mozilla.javascript.*;
import org.apache.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.security.CodeSource;
import java.security.CodeSigner;

/**
 * This class represents a JavaScript Resource.
 *
 * @author Hannes Wallnoefer <hannes@helma.at>
 */
public class ReloadableScript {

    final Trackable source;
    final RhinoEngine engine;
    final String moduleName;
    // the checksum of the underlying resource or repository when
    // the script was last compiled
    long checksum = -1;
    // true if module scope is shared
    boolean shared;
    // the compiled script
    Script script;
    // any exception that may have been thrown during compilation.
    // we keep this around in order to be able to rethrow without trying
    // to recompile if the underlying resource or repository hasn't changed
    Exception exception = null;
    List<Error> errors;
    // the loaded module scope is cached for shared modules
    ModuleScope moduleScope = null;
    // Set of direct module dependencies
    HashSet<ReloadableScript> dependencies = new HashSet<ReloadableScript>();

    private static Logger log = Logger.getLogger(ReloadableScript.class);

    /**
     * Construct a Script from the given script resource.
     *
     * @param source the JavaScript resource or repository containing the script.
     * @param engine the rhino engine
     */
    public ReloadableScript(Trackable source, RhinoEngine engine) {
        this.source = source;
        this.engine = engine;
        moduleName = source.getModuleName();
    }

    /**
     * Get the actual compiled script.
     *
     * @param cx the current Context
     * @throws JavaScriptException if an error occurred compiling the script code
     * @throws IOException if an error occurred reading the script file
     * @return the compiled and up-to-date script
     */
    public synchronized Script getScript(Context cx)
            throws JavaScriptException, IOException {
        if (!isUpToDate()) {
            if (!source.exists()) {
                throw new FileNotFoundException(source + " not found or not readable");
            }
            exception = null;
            errors = new ArrayList<Error>();
            if (source instanceof Repository) {
                script = getComposedScript(cx);
            } else {
                script = getSimpleScript(cx);
            }
        }
        if (!errors.isEmpty()) {
            ((List) cx.getThreadLocal("errors")).addAll(errors);
        }
        if (exception != null) {
            throw exception instanceof RhinoException ?
                (RhinoException) exception : new WrappedException(exception);
        }
        return script;
    }

    /**
     * Get a script from a single script file.
     * @param cx the current Context
     * @throws JavaScriptException if an error occurred compiling the script code
     * @throws IOException if an error occurred reading the script file
     * @return the compiled and up-to-date script
     */
    protected synchronized Script getSimpleScript(Context cx)
            throws JavaScriptException, IOException {
        Resource resource = (Resource) source;
        ErrorReporter errorReporter = cx.getErrorReporter();
        cx.setErrorReporter(new ErrorCollector());
        try {
            CodeSource source = engine.isPolicyEnabled() ?
                    new CodeSource(resource.getUrl(), (CodeSigner[]) null) : null;
            script = cx.compileReader(resource.getReader(), resource.getRelativePath(), 1, source);
        } catch (Exception x) {
            exception = x;
        } finally {
            cx.setErrorReporter(errorReporter);
            checksum = resource.getChecksum();
        }
        return script;
    }

    /**
     * Get the a script composed out of multiple scripts. This is a bit of a hack
     * we have in order to support helma 1 like one-directory-per-prototype like
     * compilation mode.
     *
     * @param cx the current Context
     * @throws JavaScriptException if an error occurred compiling the script code
     * @throws IOException if an error occurred reading the script file
     * @return the compiled and up-to-date script
     */
    protected synchronized Script getComposedScript(Context cx)
            throws JavaScriptException, IOException {
        Repository repository = (Repository) source;
        List<Resource> resources = repository.getResources(false);
        final List<Script> scripts = new ArrayList<Script>();
        ErrorReporter errorReporter = cx.getErrorReporter();
        cx.setErrorReporter(new ErrorCollector());
        try {
            for (Resource res: resources) {
                if (res.getName().endsWith(".js")) {
                    CodeSource source = engine.isPolicyEnabled() ?
                            new CodeSource(res.getUrl(), (CodeSigner[]) null) : null;
                    scripts.add(cx.compileReader(res.getReader(), res.getRelativePath(), 1, source));
                }
           }
        } catch (Exception x) {
            exception = x;
        } finally {
            cx.setErrorReporter(errorReporter);
            checksum = repository.getChecksum();
        }
        script =  new Script() {
            public Object exec(Context cx, Scriptable scope) {
                for (Script script: scripts) {
                    script.exec(cx, scope);
                }
                return null;
            }
        };
        return script;
    }


    /**
     * Evaluate the script on a module scope and return the result
     *
     * @param scope the scope to evaluate the script on
     * @param cx the rhino context
     * @return the result of the evaluation
     * @throws JavaScriptException if an error occurred evaluating the script file
     * @throws IOException if an error occurred reading the script file
     */
    protected Object evaluate(Scriptable scope, Context cx)
            throws JavaScriptException, IOException {
        Script script = getScript(cx);
        Map<Trackable,Scriptable> modules =
                (Map<Trackable,Scriptable>) cx.getThreadLocal("modules");
        ModuleScope module = scope instanceof ModuleScope ?
                (ModuleScope) scope : null;
        if (module != null) {
            modules.put(source, module);
        }
        Object value = script.exec(cx, scope);
        if (scope instanceof ModuleScope) {
            checkShared(module);
            module.setChecksum(getChecksum());
        }
        return value;
    }

    /**
     * Get a module scope loaded with this script
     *
     * @param prototype the prototype for the module, usually the shared top level scope
     * @param cx the rhino context
     * @return a new module scope
     * @throws JavaScriptException if an error occurred evaluating the script file
     * @throws IOException if an error occurred reading the script file
     */
    protected synchronized ModuleScope load(Scriptable prototype, Context cx)
            throws JavaScriptException, IOException {
        // check if we already came across the module in the current context/request
        Map<Trackable,ModuleScope> modules =
                (Map<Trackable,ModuleScope>) cx.getThreadLocal("modules");
        if (modules.containsKey(source)) {
            return modules.get(source);
        }
        Script script = getScript(cx);
        ModuleScope module = moduleScope;
        if (module != null) {
            // Reuse cached scope for shared modules.
            if (module.getChecksum() == getChecksum()) {
                modules.put(source, module);
                return module;
            }
            module.reset();
        } else {
            module = new ModuleScope(moduleName, source, prototype, cx);
        }
        if (log.isDebugEnabled()) {
            log.debug("Loading module: " + moduleName);
        }
        modules.put(source, module);
        script.exec(cx, module);
        checkShared(module);
        module.setChecksum(getChecksum());
        return module;
    }

    /**
     * Return true if this represents a module shared among all threads/contexts
     * @return true of this script represents a shared module
     */
    public boolean isShared() {
        return shared;
    }

    /**
     * Check if the module has the __shared__ flag set, and set the moduleScope
     * field accordingly.
     * @param module the module scope
     */
    protected void checkShared(ModuleScope module) {
        shared = module.get("__shared__", module) == Boolean.TRUE;
        if (shared) {
            engine.registerSharedScript(source, this);
            moduleScope = module;
        } else {
            engine.removeSharedScript(source);
            moduleScope = null;
        }
    }

    /**
     * Checks if the main file or any of the files it includes were updated 
     * since the script was last parsed and evaluated.
     * @return true if none of the included files has been updated since
     * we last checked.
     */
    protected boolean isUpToDate() throws IOException {
        return source.getChecksum() == checksum;
    }

    /**
     * Get the checksum of the script. For ordinary (non-shared) modules this is just
     * the checksum of the script code itself. For shared modules, it includes the
     * transitive sum of loaded module checksums, as shared modules need to be re-evaluated
     * even if just a dependency has been updated.
     * @return the evaluation checksum for this script
     */
    protected long getChecksum() throws IOException {
        long cs = checksum;
        if (shared) {
            Set<ReloadableScript> set = new HashSet<ReloadableScript>();
            for (ReloadableScript script: dependencies) {
                cs += script.getNestedChecksum(set);
            }
        }
        return cs;
    }

    /**
     * Get the recursive checksum of this script as a dependency. Since the checksum
     * field may not be up-to-date we directly get the checksum from the underlying
     * resource.
     * @param set visited script set to prevent cyclic invokation
     * @return the nested checksum
     */
    protected long getNestedChecksum(Set<ReloadableScript> set) throws IOException {
        if (set.contains(this)) {
            return 0;
        }
        set.add(this);
        long cs = source.getChecksum();
        for (ReloadableScript script: dependencies) {
            cs += script.getNestedChecksum(set);
        }
        return cs;

    }

    /**
     * Register a script that this script depends on. This means that the script
     * has been loaded directly or indirectly from the top scope of this module.
     *
     * The purpose of this is to keep track of modules loaded indirectly by shared
     * modules, as we wouldn't normally notice they have been updated.
     *
     * Scripts loaded __after__ a module has been loaded do not count as dependencies,
     * as they will be checked each time they are loaded.
     *
     * @param script a script we depend on
     */
    protected void addDependency(ReloadableScript script) {
        if (!dependencies.contains(script)) {
            dependencies.add(script);
        }
    }

    /**
     * Hash code delegates to source.
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return source.hashCode();
    }

    /**
     * Equal check delegates to source.
     * @param obj the object to compare ourself to
     * @return true if it is a script with the same resource
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof ReloadableScript
                && source.equals(((ReloadableScript) obj).source);
    }

    class ErrorCollector implements ErrorReporter {
        public void warning(String message, String sourceName,
                            int line, String lineSource, int lineOffset) {
            // errors.add(new Error(message, sourceName, line, lineSource, lineOffset));
        }

        public void error(String message, String sourceName,
                          int line, String lineSource, int lineOffset) {
            errors.add(new Error(message, sourceName, line, lineSource, lineOffset));
        }

        public EvaluatorException runtimeError(String message, String sourceName,
                                               int line, String lineSource, int lineOffset) {
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }
    }

    public static class Error {
        public final String message, sourceName, lineSource;
        public final int line, offset;

        Error(String message, String sourceName, int line, String lineSource, int offset) {
            this.message = message;
            this.sourceName = sourceName;
            this.lineSource = lineSource;
            this.line = line;
            this.offset = offset;
        }

        public String toString() {
            String lineSeparator = System.getProperty("line.separator", "\n");
            StringBuffer b = new StringBuffer(sourceName).append(", line ").append(line)
                    .append(": ").append(message).append(lineSeparator)
                    .append(lineSource).append(lineSeparator);
            for(int i = 0; i < offset - 1; i++) {
                b.append(' ');
            }
            b.append('^').append(lineSeparator);
            return b.toString();
        }
    }

}


