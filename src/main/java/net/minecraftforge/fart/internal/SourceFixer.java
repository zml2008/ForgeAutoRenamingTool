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

import net.minecraftforge.fart.api.SourceFixerConfig;
import org.objectweb.asm.ClassVisitor;

public final class SourceFixer extends OptionalChangeTransformer {
    private final SourceFixerConfig config;

    public SourceFixer(SourceFixerConfig config) {
        this.config = config;
    }

    @Override
    protected ClassFixer createFixer(ClassVisitor parent) {
        return new Fixer(config, parent);
    }

    private static class Fixer extends ClassFixer {
        private final SourceFixerConfig config;
        private String className = null;
        private boolean hadEntry = false;

        public Fixer(SourceFixerConfig config, ClassVisitor parent) {
            super(parent);
            this.config = config;
        }

        public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            this.className = name;
        }

        public void visitSource(final String source, final String debug) {
            super.visitSource(getSourceName(source), debug);
            hadEntry = true;
        }

        public void visitEnd() {
            if (!hadEntry)
                super.visitSource(getSourceName(null), null);
            super.visitEnd();
        }

        private String getSourceName(String existing) {
            String name = className;
            if (config == SourceFixerConfig.JAVA) {
                int idx = name.lastIndexOf('/');
                if (idx != -1)
                    name = name.substring(idx + 1);
                idx = name.indexOf('$');
                if (idx != -1)
                    name = name.substring(0, idx);
                name += ".java";
            }

            if (!name.equals(existing))
                madeChange = true;
            return name;
        }
    }
}
