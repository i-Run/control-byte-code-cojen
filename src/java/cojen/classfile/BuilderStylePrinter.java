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

import java.io.PrintWriter;

/**
 * Disassembles a ClassFile into a Java source file, which when run, produces
 * the original class.
 *
 * @author Brian S O'Neill
 */
class BuilderStylePrinter implements DisassemblyTool.Printer {
    private PrintWriter mOut;

    private int mIndent = 0;
    private boolean mNeedIndent = true;

    public BuilderStylePrinter() {
    }

    public void disassemble(ClassFile cf, PrintWriter out) {
        mOut = out;

        println("import java.io.BufferedOutputStream;");
        println("import java.io.FileOutputStream;");
        println("import java.io.OutputStream;");
        println();
        println("import cojen.classfile.ClassFile;");
        println("import cojen.classfile.CodeBuilder;");
        println("import cojen.classfile.FieldInfo;");
        println("import cojen.classfile.Label;");
        println("import cojen.classfile.LocalVariable;");
        println("import cojen.classfile.MethodInfo;");
        println("import cojen.classfile.Modifiers;");
        println("import cojen.classfile.Opcode;");
        println("import cojen.classfile.TypeDesc;");

        println();
        println("/**");
        println(" * @author auto-generated");
        println(" */");
        println("public class ClassFileBuilder {");
        mIndent += 4;
        
        println("public static void main(String[] args) throws Exception {");
        mIndent += 4;
        println("ClassFile cf = createClassFile();");
        println("if (args.length > 0) {");
        mIndent += 4;
        println("OutputStream out = new BufferedOutputStream(new FileOutputStream(args[0]));");
        println("cf.writeTo(out);");
        println("out.close();");
        mIndent -= 4;
        println("}");
        mIndent -= 4;
        println("}");

        println();

        println("private static ClassFile createClassFile() {");
        mIndent += 4;
        println("ClassFile cf = new ClassFile(\"" + escape(cf.getClassName())
                + "\", \"" + escape(cf.getSuperClassName()) + "\");");
        println("cf.setSourceFile(\"" + escape(cf.getSourceFile()) + "\");");

        if (cf.isSynthetic()) {
            println("cf.markSynthetic();");
        }
        if (cf.isDeprecated()) {
            println("cf.markDeprecated();");
        }

        if (!cf.getModifiers().equals(Modifiers.PUBLIC)) {
            print("cf.setModifiers(");
            printModifiers(cf);
            println(");");
        }

        String[] interfaces = cf.getInterfaces();
        for (int i=0; i<interfaces.length; i++) {
            println("cf.addInterface(\"" + escape(interfaces[i]) + "\");");
        }

        if (cf.getInitializer() != null) {
            println();
            println("createStaticInitializer(cf);");
        }

        FieldInfo[] fields = cf.getFields();
        boolean createdFieldVariable = false;

        for (int i=0; i<fields.length; i++) {
            if (i == 0) {
                println();
                println("//");
                println("// Create fields");
                println("//");
            }

            println();

            FieldInfo fi = fields[i];
            if (fi.isSynthetic() || fi.isDeprecated() || fi.getConstantValue() != null) {
                if (!createdFieldVariable) {
                    print("FieldInfo ");
                    createdFieldVariable = true;
                }
                print("fi = ");
            }

            print("cf.addField(");
            printModifiers(fi);
            print(", ");
            print('\"' + escape(fi.getName()) + "\", ");
            print(fi.getType());
            println(");");

            if (fi.getConstantValue() != null) {
                ConstantInfo constant = fi.getConstantValue();
                print("fi.setConstantValue(");
                if (constant instanceof ConstantStringInfo) {
                    print("\"");
                    String value = ((ConstantStringInfo)constant).getValue();
                    print(escape(value));
                    print("\"");
                } else if (constant instanceof ConstantIntegerInfo) {
                    print(String.valueOf(((ConstantIntegerInfo)constant).getValue()));
                } else if (constant instanceof ConstantLongInfo) {
                    print(String.valueOf(((ConstantLongInfo)constant).getValue()));
                    print("L");
                } else if (constant instanceof ConstantFloatInfo) {
                    float value = ((ConstantFloatInfo)constant).getValue();
                    if (value != value) {
                        print("0.0f/0.0f");
                    } else if (value == Float.NEGATIVE_INFINITY) {
                        print("-1.0f/0.0f");
                    } else if (value == Float.POSITIVE_INFINITY) {
                        print("1.0f/0.0f");
                    } else {
                        print(String.valueOf(value));
                        print("f");
                    }
                } else if (constant instanceof ConstantDoubleInfo) {
                    double value = ((ConstantDoubleInfo)constant).getValue();
                    if (value != value) {
                        print("0.0d/0.0d");
                    } else if (value == Float.NEGATIVE_INFINITY) {
                        print("-1.0d/0.0d");
                    } else if (value == Float.POSITIVE_INFINITY) {
                        print("1.0d/0.0d");
                    } else {
                        print(String.valueOf(value));
                        print("d");
                    }
                }
                println(");");
            }
            if (fi.isSynthetic()) {
                println("fi.markSynthetic();");
            }
            if (fi.isDeprecated()) {
                println("fi.markDeprecated();");
            }
        }

        MethodInfo[] methods = cf.getConstructors();
        for (int i=0; i<methods.length; i++) {
            if (i == 0) {
                println();
                println("//");
                println("// Create constructors");
                println("//");
            }
            println();
            println("// " + methods[i]);
            println("createConstructor_" + (i + 1) + "(cf);");
        }

        methods = cf.getMethods();
        for (int i=0; i<methods.length; i++) {
            if (i == 0) {
                println();
                println("//");
                println("// Create methods");
                println("//");
            }

            println();
            println("// " + methods[i]);
            println("createMethod_" + (i + 1) + "(cf);");
        }

        // TODO: Inner classes
        /*
        ClassFile[] innerClasses = cf.getInnerClasses();
        println("innerClasses: ");
        mIndent += 4;
        for (int i=0; i<innerClasses.length; i++) {
            disassemble(innerClasses[i], mOut);
        }
        mIndent -= 4;
        */

        println();
        println("return cf;");

        mIndent -= 4;
        println("}");

        methods = cf.getConstructors();
        for (int i=0; i<methods.length; i++) {
            println();
            println("// " + methods[i]);
            println("private static void createConstructor_" + (i + 1) + "(ClassFile cf) {");
            mIndent += 4;
            disassemble(methods[i]);
            mIndent -= 4;
            println("}");
        }

        methods = cf.getMethods();
        for (int i=0; i<methods.length; i++) {
            println();
            println("// " + methods[i]);
            println("private static void createMethod_" + (i + 1) + "(ClassFile cf) {");
            mIndent += 4;
            disassemble(methods[i]);
            mIndent -= 4;
            println("}");
        }

        mIndent -= 4;
        println("}");
    }

    private void disassemble(MethodInfo mi) {
        print("MethodInfo mi = cf.add");

        if (mi.getName().equals("<init>")) {
            print("Constructor(");
            printModifiers(mi);
            print(", ");
            print(mi.getMethodDescriptor().getParameterTypes());
            println(");");
        } else {
            print("Method(");
            printModifiers(mi);
            print(", ");
            print("\"" + escape(mi.getName()) + "\", ");
            print(mi.getMethodDescriptor().getReturnType());
            print(", ");
            print(mi.getMethodDescriptor().getParameterTypes());
            println(");");
        }

        if (mi.isSynthetic()) {
            println("mi.markSynthetic();");
        }
        if (mi.isDeprecated()) {
            println("mi.markDeprecated();");
        }
        String[] exceptions = mi.getExceptions();
        for (int j=0; j<exceptions.length; j++) {
            println("mi.addException(\"" + escape(exceptions[j]) + "\");");
        }

        if (mi.getCodeAttr() != null) {
            println("CodeBuilder b = new CodeBuilder(mi);");
            println();

            TypeDesc[] paramTypes = mi.getMethodDescriptor().getParameterTypes();
            boolean isStatic = mi.getModifiers().isStatic();
            String indentStr = generateIndent(mIndent);
            
            new CodeDisassembler(mi).disassemble
                (new CodeAssemblerPrinter(paramTypes, isStatic,
                                          mOut, indentStr, ";", "b."));
        }
    }

    private String escape(String str) {
        return CodeAssemblerPrinter.escape(str);
    }

    private void printModifiers(ClassFile cf) {
        printModifiers(cf.getModifiers());
    }

    private void printModifiers(FieldInfo fi) {
        printModifiers(fi.getModifiers());
    }

    private void printModifiers(MethodInfo mi) {
        printModifiers(mi.getModifiers());
    }

    private void printModifiers(Modifiers modifiers) {
        print("Modifiers.");

        if (modifiers.isPublic()) {
            if (modifiers.isAbstract()) {
                print("PUBLIC_ABSTRACT");
                modifiers = modifiers.toAbstract(false);
            } else if (modifiers.isStatic()) {
                print("PUBLIC_STATIC");
                modifiers = modifiers.toStatic(false);
            } else {
                print("PUBLIC");
            }
            modifiers = modifiers.toPublic(false);
        } else if (modifiers.isProtected()) {
            print("PROTECTED");
            modifiers = modifiers.toProtected(false);
        } else if (modifiers.isPrivate()) {
            print("PRIVATE");
            modifiers = modifiers.toPrivate(false);
        } else {
            print("NONE");
        }

        if (modifiers.isStatic()) {
            print(".toStatic(true)");
        }
        if (modifiers.isFinal()) {
            print(".toFinal(true)");
        }
        if (modifiers.isSynchronized()) {
            print(".toSynchronized(true)");
        }
        if (modifiers.isVolatile()) {
            print(".toVolatile(true)");
        }
        if (modifiers.isTransient()) {
            print(".toTransient(true)");
        }
        if (modifiers.isNative()) {
            print(".toNative(true)");
        }
        if (modifiers.isInterface()) {
            print(".toInterface(true)");
        }
        if (modifiers.isAbstract() && !modifiers.isInterface()) {
            print(".toAbstract(true)");
        }
        if (modifiers.isStrict()) {
            print(".toStrict(true)");
        }
    }

    private void print(TypeDesc type) {
        if (type == null || type == TypeDesc.VOID) {
            print("null");
            return;
        }

        if (type.isPrimitive()) {
            print("TypeDesc.".concat(type.getRootName().toUpperCase()));
            return;
        } else if (type == TypeDesc.OBJECT) {
            print("TypeDesc.OBJECT");
            return;
        } else if (type == TypeDesc.STRING) {
            print("TypeDesc.STRING");
            return;
        }

        TypeDesc componentType = type.getComponentType();
        if (componentType != null) {
            print(componentType);
            print(".toArrayType()");
        } else {
            print("TypeDesc.forClass(\"");
            print(escape(type.getRootName()));
            print("\")");
        }
    }

    private void print(TypeDesc[] params) {
        if (params == null || params.length == 0) {
            print("null");
            return;
        }

        print("new TypeDesc[] {");

        for (int i=0; i<params.length; i++) {
            if (i > 0) {
                print(", ");
            }
            print(params[i]);
        }

        print("}");
    }

    private void print(String text) {
        indent();
        mOut.print(text);
    }

    private void println(String text) {
        print(text);
        println();
    }

    private void println() {
        mOut.println();
        mNeedIndent = true;
    }

    private void indent() {
        if (mNeedIndent) {
            for (int i=mIndent; --i>= 0; ) {
                mOut.print(' ');
            }
            mNeedIndent = false;
        }
    }

    private String generateIndent(int amount) {
        StringBuffer buf = new StringBuffer(amount);
        for (int i=0; i<amount; i++) {
            buf.append(' ');
        }
        return buf.toString();
    }
}
