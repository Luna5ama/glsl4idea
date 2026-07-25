package glslplugin.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.usageView.UsageViewNodeTextLocation;
import glslplugin.LightGLSLTestCase;
import glslplugin.extensions.GLSLDescriptionProvider;
import glslplugin.lang.elements.declarations.GLSLFunctionDeclaration;
import glslplugin.lang.elements.declarations.GLSLFunctionDeclarationImpl;
import glslplugin.lang.elements.declarations.GLSLFunctionDefinition;
import glslplugin.lang.elements.declarations.GLSLStructDefinition;
import glslplugin.lang.elements.expressions.GLSLFunctionOrConstructorCallExpression;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FunctionCallOverloadTest extends LightGLSLTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), new File(".intellijPlatform/sandbox").getAbsolutePath());
    }

    public void testIncompleteCallKeepsParameterListAndFunctionCandidates() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void main() {
                    foo(
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        assertNotNull(call.getParameterList());
        assertEquals(1, call.getFunctionCandidates().length);
        assertFalse(call.getFunctionCandidates()[0].isApplicable());
        assertTrue(call.getFunctionCandidates()[0].isPrefixApplicable());
        assertFalse(myFixture.doHighlighting().stream().anyMatch(FunctionCallOverloadTest::isInapplicableOverloadError));
    }

    public void testMissingArgumentsStillResolveToAllOverloadCandidates() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void foo(int sss, double aaa) {
                }

                void main() {
                    foo();
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);

        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(2, results.length);
        assertFalse(results[0].isValidResult());
        assertFalse(results[1].isValidResult());
        assertEquals(2, call.getFunctionCandidates().length);
    }

    public void testSingleApplicableOverloadStillResolves() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void foo(int sss, double aaa) {
                }

                void main() {
                    foo(1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        assertEquals(2, reference.multiResolve(false).length);

        final PsiElement resolved = reference.resolve();
        assertTrue(resolved instanceof GLSLFunctionDeclaration);
        assertEquals(1, ((GLSLFunctionDeclaration) resolved).getParameters().length);
    }

    public void testDirectOverloadIsPreferredOverImplicitOverload() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void foo(double sss) {
                }

                void main() {
                    foo(1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(2, results.length);
        assertEquals("foo(int) : void", ((GLSLFunctionDeclaration) results[0].getElement()).getSignature());
        assertTrue(results[0].isValidResult());
        assertEquals("foo(double) : void", ((GLSLFunctionDeclaration) results[1].getElement()).getSignature());
        assertFalse(results[1].isValidResult());

        final PsiElement resolved = reference.resolve();
        assertTrue(resolved instanceof GLSLFunctionDeclaration);
        assertEquals("foo(int) : void", ((GLSLFunctionDeclaration) resolved).getSignature());
    }

    public void testDirectOverloadProvidesResolvedReturnType() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                int foo(int value) {
                    return value;
                }

                double foo(double value) {
                    return value;
                }

                void main() {
                    foo(1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);

        final PsiElement resolved = reference.resolve();
        assertTrue(resolved instanceof GLSLFunctionDeclaration);
        assertEquals("foo(int) : int", ((GLSLFunctionDeclaration) resolved).getSignature());
        assertEquals("int", call.getType().getTypename());
    }

    public void testBestImplicitOverloadIsUniquelyValid() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                float foo(float value) {
                    return value;
                }

                double foo(double value) {
                    return value;
                }

                void main() {
                    foo(1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(2, results.length);
        assertEquals(1, java.util.Arrays.stream(results).filter(ResolveResult::isValidResult).count());

        final PsiElement resolved = reference.resolve();
        assertTrue(resolved instanceof GLSLFunctionDeclaration);
        assertEquals("foo(float) : float", ((GLSLFunctionDeclaration) resolved).getSignature());
        assertEquals("float", call.getType().getTypename());
    }

    public void testVectorImplicitOverloadUsesScalarBasePreference() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                vec2 foo(vec2 value) {
                    return value;
                }

                dvec2 foo(dvec2 value) {
                    return value;
                }

                void main() {
                    ivec2 value;
                    foo(value);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = onlyCall().getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(1, java.util.Arrays.stream(results).filter(ResolveResult::isValidResult).count());
        assertEquals("foo(vec2) : vec2", ((GLSLFunctionDeclaration) reference.resolve()).getSignature());
    }

    public void testImplicitOverloadMustDominateEveryCandidate() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                float foo(float first, double second) {
                    return first;
                }

                double foo(double first, float second) {
                    return first;
                }

                void main() {
                    foo(1, 1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = onlyCall().getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(2, results.length);
        assertEquals(0, java.util.Arrays.stream(results).filter(ResolveResult::isValidResult).count());
        assertNull(reference.resolve());
    }

    public void testApplicableCallIsNotReferenceToIncompatibleOverload() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int value) {
                }

                void foo(int value, int other) {
                }

                void main() {
                    foo(1);
                }
                """);

        GLSLFunctionDeclaration applicable = null;
        GLSLFunctionDeclaration incompatible = null;
        for (GLSLFunctionDeclaration declaration :
                PsiTreeUtil.findChildrenOfType(myFixture.getFile(), GLSLFunctionDeclaration.class)) {
            if (!"foo".equals(declaration.getFunctionName())) continue;
            if (declaration.getParameters().length == 1) {
                applicable = declaration;
            } else if (declaration.getParameters().length == 2) {
                incompatible = declaration;
            }
        }
        assertNotNull(applicable);
        assertNotNull(incompatible);

        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = onlyCall().getReference();
        assertNotNull(reference);
        assertTrue(reference.isReferenceTo(applicable));
        assertFalse(reference.isReferenceTo(incompatible));
    }

    public void testLocalStructConstructorShadowsBuiltInFunction() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void main() {
                    struct sin {
                        float x;
                    };
                    sin value = sin(1.0);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        assertTrue(reference.resolve() instanceof GLSLStructDefinition);
        assertEquals(0, call.getFunctionCandidates().length);
        assertTrue(call.isConstructor());
        assertFalse(myFixture.doHighlighting().stream().anyMatch(FunctionCallOverloadTest::isInapplicableOverloadError));
    }

    public void testReturnOnlyRedeclarationIsNotAnOverload() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                float foo(int value);

                int foo(int value) {
                    return value;
                }

                void main() {
                    foo(1);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        assertEquals(1, call.getFunctionCandidates().length);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(1, results.length);
        assertTrue(results[0].isValidResult());
        assertTrue(results[0].getElement() instanceof GLSLFunctionDefinition);
        assertEquals("foo(int) : int", ((GLSLFunctionDeclaration) results[0].getElement()).getSignature());
    }

    public void testUnnamedPrototypeAndNamedDefinitionAreOneCandidate() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float);

                void foo(float value) {
                }

                void main() {
                    foo(1.0);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        assertEquals(1, call.getFunctionCandidates().length);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(1, results.length);
        assertTrue(results[0].isValidResult());
        assertTrue(results[0].getElement() instanceof GLSLFunctionDefinition);
    }

    public void testNestedAmbiguousCallsDoNotResolveExponentially() {
        String expression = "missing()";
        for (int i = 0; i < 18; i++) {
            expression = "foo(" + expression + ")";
        }
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                int foo(int value) {
                    return value;
                }

                float foo(float value) {
                    return value;
                }

                void main() {
                    %s;
                }
                """.formatted(expression));

        final GLSLFunctionOrConstructorCallExpression outermostCall =
                PsiTreeUtil.findChildrenOfType(myFixture.getFile(), GLSLFunctionOrConstructorCallExpression.class)
                        .stream()
                        .filter(call -> "foo".equals(call.getFunctionOrConstructedTypeName()))
                        .max(Comparator.comparingInt(PsiElement::getTextLength))
                        .orElseThrow();

        final long started = System.nanoTime();
        assertFalse(outermostCall.getType().isValidType());
        final long elapsed = System.nanoTime() - started;
        assertTrue("Nested overload resolution took " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms",
                elapsed < TimeUnit.SECONDS.toNanos(10));
    }

    public void testArrayOverloadSignaturesRemainDistinct() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float value) {
                }

                void foo(float values[2]) {
                }
                """);

        String scalarSignature = null;
        String arraySignature = null;
        for (GLSLFunctionDeclaration declaration :
                PsiTreeUtil.findChildrenOfType(myFixture.getFile(), GLSLFunctionDeclaration.class)) {
            if (!"foo".equals(declaration.getFunctionName())) continue;
            final String parameterType = declaration.getFunctionType().getParameterTypes()[0].getTypename();
            if ("float".equals(parameterType)) {
                scalarSignature = declaration.getSignature();
            } else {
                arraySignature = declaration.getSignature();
            }
        }
        assertNotNull(scalarSignature);
        assertNotNull(arraySignature);

        assertEquals("foo(float) : void", scalarSignature);
        assertEquals("foo(float[2]) : void", arraySignature);
    }

    public void testArrayDeclarationAndDefinitionResolveAsOneCandidate() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float values[2]);

                void foo(float values[2]) {
                }

                void main() {
                    float values[2];
                    foo(values);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(1, results.length);
        assertTrue(results[0].isValidResult());
        assertNotNull(reference.resolve());
        assertEquals("void", call.getType().getTypename());
    }

    public void testSizedArrayPrototypeNavigatesToMatchingDefinition() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void choose(float values[2]);

                void choose(float values[2]) {
                }

                void choose(int values[2]) {
                }
                """);

        final GLSLFunctionDeclarationImpl prototype =
                PsiTreeUtil.findChildOfType(myFixture.getFile(), GLSLFunctionDeclarationImpl.class);
        assertNotNull(prototype);
        final ResolveResult[] results = prototype.getReference().multiResolve(false);
        assertEquals(2, results.length);
        assertEquals(1, java.util.Arrays.stream(results).filter(ResolveResult::isValidResult).count());
        final ResolveResult valid = java.util.Arrays.stream(results)
                .filter(ResolveResult::isValidResult)
                .findFirst()
                .orElseThrow();
        assertEquals("choose(float[2]) : void", ((GLSLFunctionDeclaration) valid.getElement()).getSignature());
    }

    public void testArrayOverloadResolutionIncludesElementType() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float values[2]) {
                }

                void foo(int values[2]) {
                }

                void main() {
                    int values[2];
                    foo(values);
                }
                """);

        final GLSLFunctionOrConstructorCallExpression call = onlyCall();
        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference reference = call.getReference();
        assertNotNull(reference);
        final ResolveResult[] results = reference.multiResolve(false);
        assertEquals(2, results.length);
        assertEquals(1, java.util.Arrays.stream(results).filter(ResolveResult::isValidResult).count());

        final PsiElement resolved = reference.resolve();
        assertTrue(resolved instanceof GLSLFunctionDeclaration);
        assertEquals("foo(int[2]) : void", ((GLSLFunctionDeclaration) resolved).getSignature());
    }

    public void testDeepArrayDimensionFormatsWithoutExponentialEvaluation() {
        String dimension = "1";
        for (int i = 0; i < 22; i++) {
            dimension += " + 1";
        }
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float values[%s]) {
                }
                """.formatted(dimension));

        final GLSLFunctionDeclaration declaration =
                PsiTreeUtil.findChildOfType(myFixture.getFile(), GLSLFunctionDeclaration.class);
        assertNotNull(declaration);

        final long started = System.nanoTime();
        assertEquals("float[23]", declaration.getParameters()[0].getCompleteTypeName());
        final long elapsed = System.nanoTime() - started;
        assertTrue("Array dimension formatting took " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms",
                elapsed < TimeUnit.SECONDS.toNanos(2));
    }

    public void testVeryDeepBinaryArrayDimensionIsStackSafe() {
        final int additions = 8192;
        final String dimension = "1 + ".repeat(additions) + "1";
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float values[%s]) {
                }
                """.formatted(dimension));

        final GLSLFunctionDeclaration declaration =
                PsiTreeUtil.findChildOfType(myFixture.getFile(), GLSLFunctionDeclaration.class);
        assertNotNull("Parsing did not produce the function declaration", declaration);
        assertEquals("float[8193]", declaration.getParameters()[0].getCompleteTypeName());
    }

    public void testDeepUnaryArrayDimensionFormatsWithoutExponentialEvaluation() {
        String dimension = "1";
        for (int i = 0; i < 24; i++) {
            dimension = "- " + dimension;
        }
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(float values[%s]) {
                }
                """.formatted(dimension));

        final GLSLFunctionDeclaration declaration =
                PsiTreeUtil.findChildOfType(myFixture.getFile(), GLSLFunctionDeclaration.class);
        assertNotNull(declaration);

        final long started = System.nanoTime();
        assertEquals("float[1]", declaration.getParameters()[0].getCompleteTypeName());
        final long elapsed = System.nanoTime() - started;
        assertTrue("Unary array dimension formatting took " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms",
                elapsed < TimeUnit.SECONDS.toNanos(2));
    }

    public void testTrailingCommaParserErrorDoesNotAddOverloadError() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int first, int second) {
                }

                void main() {
                    foo(1,);
                }
                """);

        assertNotNull(PsiTreeUtil.findChildOfType(onlyCall().getParameterList(), PsiErrorElement.class));
        assertFalse(myFixture.doHighlighting().stream().anyMatch(FunctionCallOverloadTest::isInapplicableOverloadError));
    }

    public void testFunctionDescriptionUsesSignatureForNodeText() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss, double aaa) {
                }
                """);

        final GLSLFunctionDeclaration declaration = PsiTreeUtil.findChildOfType(myFixture.getFile(), GLSLFunctionDeclaration.class);
        assertNotNull(declaration);
        assertEquals(
                "foo(int,double) : void",
                new GLSLDescriptionProvider().getElementDescription(declaration, UsageViewNodeTextLocation.INSTANCE)
        );
    }

    public void testFunctionNavigationPresentationUsesSignature() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void foo(int sss, double aaa) {
                }
                """);

        final GLSLFunctionDeclaration[] declarations = PsiTreeUtil.findChildrenOfType(
                myFixture.getFile(),
                GLSLFunctionDeclaration.class
        ).toArray(GLSLFunctionDeclaration[]::new);
        assertEquals(2, declarations.length);

        final ItemPresentation firstPresentation = declarations[0].getPresentation();
        final ItemPresentation secondPresentation = declarations[1].getPresentation();
        assertNotNull(firstPresentation);
        assertNotNull(secondPresentation);
        assertEquals("foo(int) : void", firstPresentation.getPresentableText());
        assertEquals("foo(int,double) : void", secondPresentation.getPresentableText());
    }

    public void testMissingArgumentAnnotationListsOverloadCandidates() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int sss) {
                }

                void foo(int sss, double aaa) {
                }

                void main() {
                    foo();
                }
                """);

        final List<HighlightInfo> highlights = myFixture.doHighlighting();
        boolean found = false;
        for (HighlightInfo highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR
                    && "None of the following candidates is applicable".equals(highlight.getDescription())) {
                found = true;
                break;
            }
        }
        assertTrue("Missing overload argument error", found);
    }

    public void testMissingArgumentAnnotationLimitsManyOverloadCandidates() {
        myFixture.configureByText(GLSLFileType.INSTANCE, """
                void foo(int a0) {
                }

                void foo(int a0, int a1) {
                }

                void foo(int a0, int a1, int a2) {
                }

                void foo(int a0, int a1, int a2, int a3) {
                }

                void foo(int a0, int a1, int a2, int a3, int a4) {
                }

                void foo(int a0, int a1, int a2, int a3, int a4, int a5) {
                }

                void main() {
                    foo();
                }
                """);

        final List<HighlightInfo> highlights = myFixture.doHighlighting();
        String tooltip = null;
        for (HighlightInfo highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR
                    && "None of the following candidates is applicable".equals(highlight.getDescription())) {
                tooltip = highlight.getToolTip();
                break;
            }
        }
        assertNotNull("Missing overload argument error", tooltip);
        assertTrue(tooltip.contains("Showing 5 best matches of 6 candidates."));
        assertFalse(tooltip.contains("a5"));
    }

    private GLSLFunctionOrConstructorCallExpression onlyCall() {
        final Collection<GLSLFunctionOrConstructorCallExpression> calls =
                PsiTreeUtil.findChildrenOfType(myFixture.getFile(), GLSLFunctionOrConstructorCallExpression.class);
        assertEquals(1, calls.size());
        return calls.iterator().next();
    }

    private static boolean isInapplicableOverloadError(HighlightInfo highlight) {
        return highlight.getSeverity() == HighlightSeverity.ERROR
                && "None of the following candidates is applicable".equals(highlight.getDescription());
    }
}
