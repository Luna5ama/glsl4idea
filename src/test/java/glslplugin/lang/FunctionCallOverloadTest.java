package glslplugin.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import glslplugin.LightGLSLTestCase;
import glslplugin.lang.elements.expressions.GLSLFunctionOrConstructorCallExpression;

import java.io.File;
import java.util.Collection;
import java.util.List;

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
}
