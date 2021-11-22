/*
 * Forge Auto Renaming Tool
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fart.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraftforge.fart.api.Inheritance;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.RecordComponentNode;

public class RecordFixer extends OptionalChangeTransformer {
    private final Consumer<String> debug;
    private final Inheritance inh;

    public RecordFixer(final Consumer<String> debug, final Inheritance inh) {
        this.debug = debug;
        this.inh = inh;
    }

    @Override
    protected ClassFixer createFixer(ClassVisitor parent) {
        return new Fixer(parent);
    }

    private class Fixer extends ClassFixer {
        private Map<String, RecordComponentNode> components;
        private TypeParameterCollector paramCollector;
        private boolean isRecord;
        private boolean hasRecordComponents;
        private boolean hasSignature;
        private final ClassVisitor originalParent;
        private ClassNode node;

        public Fixer(ClassVisitor parent) {
            super(parent);
            this.originalParent = parent;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.isRecord = "java/lang/Record".equals(superName);
            if (this.isRecord) {
                node = new ClassNode();
                this.cv = node;
            }
            this.hasSignature = signature != null;
            // todo: validate type parameters from superinterfaces
            // this would need to get signature information from bytecode + runtime classes
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            this.hasRecordComponents = true;
            if (signature != null && !hasSignature) { // signature implies non-primitive type
                if (paramCollector == null) paramCollector = new TypeParameterCollector();
                paramCollector.baseType = Type.getType(descriptor);
                paramCollector.param = TypeParameterCollector.FIELD;
                new SignatureReader(signature).accept(paramCollector);
            }
            return super.visitRecordComponent(name, descriptor, signature);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            // We want any fields that are final and not static. Proguard sometimes increases the visibility of record component fields to be higher than private.
            // These fields still need to have record components generated, so we need to ignore ACC_PRIVATE.
            if (isRecord && (access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == Opcodes.ACC_FINAL) {
                // Make sure the visibility gets set back to private
                access = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                // Manually add the record component back if this class doesn't have any
                if (components == null)
                    components = new LinkedHashMap<>();
                components.put(name + descriptor, new RecordComponentNode(name, descriptor, signature));
            }
            if (isRecord && signature != null && !hasSignature) { // signature implies non-primitive type
                if (paramCollector == null) paramCollector = new TypeParameterCollector();
                paramCollector.baseType = Type.getType(descriptor);
                paramCollector.param = TypeParameterCollector.FIELD;
                new SignatureReader(signature).accept(paramCollector);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (isRecord && signature != null && !hasSignature) { // signature implies non-primitive type
                if (paramCollector == null) paramCollector = new TypeParameterCollector();
                paramCollector.baseType = Type.getType(descriptor);
                new SignatureReader(signature).accept(paramCollector);
                if (paramCollector.declaredParams != null) {
                    paramCollector.declaredParams.clear();
                }
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (isRecord && !hasRecordComponents && components != null) {
                List<RecordComponentNode> nodes = new ArrayList<>(this.components.size());
                for (RecordComponentNode entry : this.components.values()) {
                    nodes.add(entry);
                    this.madeChange = true;
                }
                this.node.recordComponents = nodes;
            }
            if (isRecord && !hasSignature && paramCollector != null && !paramCollector.typeParameters.isEmpty()) {
                // Proguard also strips the Signature attribute, so we have to reconstruct that, to a point where this class is accepted by
                // javac when on the classpath. This requires every type parameter referenced to have been declared within the class.
                // Records are implicitly static and have a defined superclass of java/lang/Record, so there can be type parameters in play from:
                // - fields
                // - methods (which can declare their own formal parameters)
                // - record components
                // - superinterfaces (less important, we just get raw type warnings)
                //
                // This will not be perfect, but provides enough information to allow compilation and enhance decompiler output.
                // todo: allow type-specific rules to infer deeper levels (for example, T with raw type Comparable is probably Comparable<T>)

                final SignatureWriter sw = new SignatureWriter();
                // Formal parameters
                // find all used type parameters, plus guesstimated bounds
                for (Map.Entry<String, String> param : paramCollector.typeParameters.entrySet()) {
                    sw.visitFormalTypeParameter(param.getKey());
                    if (!param.getValue().equals(TypeParameterCollector.UNKNOWN)) {
                        final Inheritance.IClassInfo cls = inh.getClass(param.getValue()).orElse(null);
                        if (cls != null) {
                            SignatureVisitor parent;
                            if ((cls.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
                                parent = sw.visitInterfaceBound();
                            } else {
                                parent = sw.visitClassBound();
                            }
                            parent.visitClassType(param.getValue());
                            parent.visitEnd();
                            continue;
                        } else {
                            debug.accept("Unable to find information for type " + param.getValue());
                        }
                    }
                    SignatureVisitor cls = sw.visitClassBound();
                    cls.visitClassType("java/lang/Object");
                    cls.visitEnd();
                }

                // Supertype (always Record)
                final SignatureVisitor sv = sw.visitSuperclass();
                sv.visitClassType(node.superName);
                sv.visitEnd();

                // Superinterfaces
                for (final String superI : node.interfaces) {
                    final SignatureVisitor itfV = sw.visitInterface();
                    itfV.visitClassType(superI);
                    sv.visitEnd();
                }
                String newSignature = sw.toString();
                debug.accept("New signature for " + node.name + ": " + newSignature);
                node.signature = newSignature;
                this.madeChange = true;
            }
            // feed node through to the original output visitor
            if (node != null && originalParent != null) {
                node.accept(originalParent);
            }
        }
    }

    static class TypeParameterCollector extends SignatureVisitor {
        private static final int RETURN_TYPE = -2;
        static final int FIELD = -1;
        static final String UNKNOWN = "???";
        Map<String, String> typeParameters = new HashMap<>(); // <Parameter, FieldType>
        Type baseType;
        int param = -1;
        int level;
        Set<String> declaredParams;

        public TypeParameterCollector() {
            super(RenamerImpl.MAX_ASM_VERSION);
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            if (declaredParams == null)
                declaredParams = new HashSet<>();
            declaredParams.add(name);
        }

        @Override
        public void visitTypeVariable(String name) {
            if (!typeParameters.containsKey(name) || typeParameters.get(name).equals(UNKNOWN)) {
                if (level == 0 && baseType != null && (declaredParams == null || !declaredParams.contains(name))) {
                    String typeName;
                    switch (param) {
                        case FIELD: // field
                            typeName = baseType.getInternalName();
                            break;
                        case RETURN_TYPE: // method return value
                            typeName = baseType.getReturnType().getInternalName();
                            break;
                        default:
                            typeName = baseType.getArgumentTypes()[param].getInternalName();
                            break;
                    }
                    typeParameters.put(name, typeName);
                } else {
                    typeParameters.put(name, UNKNOWN);
                }
            }
            super.visitTypeVariable(name);
        }

        @Override
        public void visitClassType(String name) {
            level++;
            super.visitClassType(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            level++;
            super.visitInnerClassType(name);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            level++;
            return super.visitTypeArgument(wildcard);
        }

        @Override
        public void visitEnd() {
            if (level-- <= 0) {
                throw new IllegalStateException("Unbalanced signature levels");
            }
            super.visitEnd();
        }

        // for methods

        @Override
        public SignatureVisitor visitParameterType() {
            this.param++;
            return super.visitParameterType();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            this.param = RETURN_TYPE;
            return super.visitReturnType();
        }
    }
}
