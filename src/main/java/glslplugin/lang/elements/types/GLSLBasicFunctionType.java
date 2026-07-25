/*
 *     Copyright 2010 Jean-Paul Balabanian and Yngve Devik Hammersland
 *
 *     This file is part of glsl4idea.
 *
 *     Glsl4idea is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as
 *     published by the Free Software Foundation, either version 3 of
 *     the License, or (at your option) any later version.
 *
 *     Glsl4idea is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with glsl4idea.  If not, see <http://www.gnu.org/licenses/>.
 */

package glslplugin.lang.elements.types;

import glslplugin.lang.elements.declarations.GLSLFunctionDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * FunctionType for functions (= not constructors)
 *
 * @author Yngve Devik Hammersland
 *         Date: Mar 2, 2009
 *         Time: 12:20:32 PM
 */
public final class GLSLBasicFunctionType extends GLSLFunctionType {

    private final GLSLType[] parameterTypes;

    public GLSLBasicFunctionType(@Nullable GLSLFunctionDeclaration definition,
                                 @NotNull String name, @NotNull GLSLType returnType,
                                 @NotNull GLSLType... parameterTypes) {
        super(name, returnType, definition);
        this.parameterTypes = parameterTypes;
    }

    public GLSLBasicFunctionType(@NotNull String name, @NotNull GLSLType returnType,
                                 @NotNull GLSLType... parameterTypes) {
        super(name, returnType, null);
        this.parameterTypes = parameterTypes;
    }

    protected String generateTypename() {
        StringBuilder b = new StringBuilder();
        b.append(getReturnType().getTypename()).append(' ').append(getName());
        b.append('(');
        boolean first = true;
        for (GLSLType type : parameterTypes) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(type.getTypename());
        }
        b.append(")");
        return b.toString();
    }

    @NotNull
    public GLSLTypeCompatibilityLevel getParameterCompatibilityLevel(@NotNull GLSLType[] types) {
        return GLSLTypeCompatibilityLevel.getCompatibilityLevel(types, parameterTypes);
    }

    @NotNull
    public GLSLType[] getParameterTypes() {
        return parameterTypes;
    }

    /** @return true if this could be a valid declaration for the other definition (or vice versa) */
    public boolean definitionsMatch(GLSLBasicFunctionType other) {
        if (this == other) return true;
        if (!other.getName().equals(getName()) || parameterTypes.length != other.parameterTypes.length) return false;
        for (int i = 0; i < parameterTypes.length; i++) {
            final GLSLType first = parameterTypes[i];
            final GLSLType second = other.parameterTypes[i];
            if (first instanceof GLSLArrayType firstArray && second instanceof GLSLArrayType secondArray) {
                if (!Arrays.equals(firstArray.getDimensions(), secondArray.getDimensions())
                        || !firstArray.getBaseType().typeEquals(secondArray.getBaseType())) return false;
            } else if (!first.typeEquals(second)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof GLSLBasicFunctionType other
                && other.getName().equals(getName())
                && Arrays.equals(parameterTypes, other.parameterTypes));
    }

    @Override
    public int hashCode() {
        return getName().hashCode() * 31 + Arrays.hashCode(parameterTypes);
    }
}
