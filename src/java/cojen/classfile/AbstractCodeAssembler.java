/*
 *  Copyright 2004 Brian S O'Neill
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

package cojen.classfile;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.MissingResourceException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractCodeAssembler implements CodeAssembler {
    protected AbstractCodeAssembler() {
    }

    public void inline(Object code) {
        // First load the class for the inlined code.

        Class codeClass = code.getClass();
        String className = codeClass.getName().replace('.', '/') + ".class";
        ClassLoader loader = codeClass.getClassLoader();

        InputStream in;
        if (loader == null) {
            in = ClassLoader.getSystemResourceAsStream(className);
        } else {
            in = loader.getResourceAsStream(className);
        }

        if (in == null) {
            throw new MissingResourceException("Unable to find class file", className, null);
        }

        ClassFile cf;
        try {
            cf = ClassFile.readFrom(in);
        } catch (IOException e) {
            MissingResourceException e2 = new MissingResourceException
                ("Error loading class file: " + e.getMessage(), className, null);
            try {
                e2.initCause(e);
            } catch (NoSuchMethodError e3) {
            }
            throw e2;
        }

        // Now find the single "define" method.
        MethodInfo defineMethod = null;

        MethodInfo[] methods = cf.getMethods();
        for (int i=0; i<methods.length; i++) {
            MethodInfo method = methods[i];
            if ("define".equals(method.getName())) {
                if (defineMethod != null) {
                    throw new IllegalArgumentException("Multiple define methods found");
                } else {
                    defineMethod = method;
                }
            }
        }

        if (defineMethod == null) {
            throw new IllegalArgumentException("No define method found");
        }

        // Copy stack arguments to expected local variables.
        TypeDesc[] paramTypes = defineMethod.getMethodDescriptor().getParameterTypes();
        LocalVariable[] paramVars = new LocalVariable[paramTypes.length];
        for (int i=paramVars.length; --i>=0; ) {
            LocalVariable paramVar = createLocalVariable(null, paramTypes[i]);
            storeLocal(paramVar);
            paramVars[i] = paramVar;
        }

        Label returnLocation = createLabel();
        CodeDisassembler cd = new CodeDisassembler(defineMethod);
        cd.disassemble(this, paramVars, returnLocation);
        returnLocation.setLocation();
    }

    public void invoke(Method method) {
        TypeDesc ret = TypeDesc.forClass(method.getReturnType());

        Class[] paramClasses = method.getParameterTypes();
        TypeDesc[] params = new TypeDesc[paramClasses.length];
        for (int i=0; i<params.length; i++) {
            params[i] = TypeDesc.forClass(paramClasses[i]);
        }

        Class clazz = method.getDeclaringClass();

        if (Modifier.isStatic(method.getModifiers())) {
            invokeStatic(clazz.getName(),
                         method.getName(),
                         ret, 
                         params);
        } else if (clazz.isInterface()) {
            invokeInterface(clazz.getName(),
                            method.getName(),
                            ret, 
                            params);
        } else {
            invokeVirtual(clazz.getName(),
                          method.getName(),
                          ret, 
                          params);
        }
    }

    public void invokeSuper(Method method) {
        TypeDesc ret = TypeDesc.forClass(method.getReturnType());

        Class[] paramClasses = method.getParameterTypes();
        TypeDesc[] params = new TypeDesc[paramClasses.length];
        for (int i=0; i<params.length; i++) {
            params[i] = TypeDesc.forClass(paramClasses[i]);
        }

        invokeSuper(method.getDeclaringClass().getName(),
                    method.getName(),
                    ret, 
                    params);
    }

    public void invoke(Constructor constructor) {
        Class[] paramClasses = constructor.getParameterTypes();
        TypeDesc[] params = new TypeDesc[paramClasses.length];
        for (int i=0; i<params.length; i++) {
            params[i] = TypeDesc.forClass(paramClasses[i]);
        }

        invokeConstructor(constructor.getDeclaringClass().getName().toString(), params);
    }
}