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

package glslplugin.lang.elements.expressions;

import com.intellij.lang.ASTNode;
import glslplugin.lang.elements.expressions.operator.GLSLOperator;
import glslplugin.lang.elements.types.GLSLType;
import glslplugin.lang.elements.types.GLSLTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/**
 * GLSLBinaryOperatorExpression is an expression from two operands and one operator between them.
 *
 * @author Yngve Devik Hammersland
 *         Date: Jan 28, 2009
 *         Time: 3:17:16 PM
 */
public class GLSLBinaryOperatorExpression extends GLSLOperatorExpression {
    public GLSLBinaryOperatorExpression(@NotNull ASTNode astNode) {
        super(astNode);
    }

    @Nullable
    public GLSLExpression getLeftOperand() {
        GLSLExpression[] operands = getOperands();
        if (operands.length == 2) {
            return operands[0];
        } else {
            return null;
        }
    }

    @Nullable
    public GLSLExpression getRightOperand() {
        GLSLExpression[] operands = getOperands();
        if (operands.length == 2) {
            return operands[1];
        } else {
            return null;
        }
    }

    @Override
    public boolean isConstantValue() {
        return getConstantValue() != null;
    }

    @Nullable
    @Override
    public Object getConstantValue() {
        final ArrayDeque<GLSLExpression> pending = new ArrayDeque<>();
        final IdentityHashMap<GLSLExpression, ConstantEvaluation> evaluations = new IdentityHashMap<>();
        final IdentityHashMap<GLSLBinaryOperatorExpression, GLSLExpression[]> expanded = new IdentityHashMap<>();
        pending.push(this);

        while (!pending.isEmpty()) {
            final GLSLExpression expression = pending.pop();
            if (!(expression instanceof GLSLBinaryOperatorExpression binaryExpression)) {
                final Object value = expression.getConstantValue();
                final GLSLType type = expression.getType();
                if (value == null || !type.isValidType()) return null;
                evaluations.put(expression, new ConstantEvaluation(value, type));
                continue;
            }

            final GLSLExpression[] operands = expanded.remove(binaryExpression);
            if (operands == null) {
                final GLSLExpression[] binaryOperands = binaryExpression.getOperands();
                if (binaryOperands.length != 2) return null;
                expanded.put(binaryExpression, binaryOperands);
                pending.push(binaryExpression);
                pending.push(binaryOperands[1]);
                pending.push(binaryOperands[0]);
                continue;
            }

            final ConstantEvaluation left = evaluations.remove(operands[0]);
            final ConstantEvaluation right = evaluations.remove(operands[1]);
            final GLSLOperator operator = binaryExpression.getOperator();
            if (left == null || right == null
                    || !(operator instanceof GLSLOperator.GLSLBinaryOperator binaryOperator)
                    || !binaryOperator.isValidInput(left.type, right.type)) return null;
            final Object value = binaryOperator.getResultValue(left.value, right.value);
            final GLSLType type = binaryOperator.getResultType(left.type, right.type);
            if (value == null || !type.isValidType()) return null;
            evaluations.put(binaryExpression, new ConstantEvaluation(value, type));
        }

        final ConstantEvaluation result = evaluations.get(this);
        return result == null ? null : result.value;
    }

    private record ConstantEvaluation(@NotNull Object value, @NotNull GLSLType type) {
    }

    @NotNull
    @Override
    public GLSLType getType() {
        GLSLOperator operator = getOperator();
        if (operator instanceof GLSLOperator.GLSLBinaryOperator binaryOperator) {
            GLSLExpression leftOperand = getLeftOperand();
            GLSLExpression rightOperand = getRightOperand();
            return binaryOperator.getResultType(leftOperand == null ? GLSLTypes.UNKNOWN_TYPE : leftOperand.getType(),
                    rightOperand == null ? GLSLTypes.UNKNOWN_TYPE : rightOperand.getType());
        } else {
            return GLSLTypes.UNKNOWN_TYPE;
        }
    }

    public String toString() {
        GLSLOperator operator = getOperator();
        if (operator == null) {
            return "Binary Operator: '(unknown)'";
        } else {
            return "Binary Operator: '" + operator.getTextRepresentation() + "'";
        }
    }
}
