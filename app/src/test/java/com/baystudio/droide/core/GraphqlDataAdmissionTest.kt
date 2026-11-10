package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class GraphqlDataAdmissionTest {
    private fun denied(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    @Test fun onlyLiteralOriginalEdges() {
        assertEquals(setOf("graphql","./parser"),LocalUbuntuGraphqlDataAdmission.literalRequires("// require('evil')\nconst text=\"require('fake')\"; require('graphql'); require('./parser');"))
        for(source in listOf("require(name)","const r=require;", "module.require('fs')", "import('fs')", "eval('require(x)')", "new Function('x')"))denied{LocalUbuntuGraphqlDataAdmission.literalRequires(source)}
    }
    @Test fun resolutionNeverLeavesExactOriginalRoot() {
        val parent="node_modules/graphql-language-service/dist/index.js";val child="node_modules/graphql-language-service/dist/parser/index.js"
        assertEquals(child,LocalUbuntuGraphqlDataAdmission.resolve(parent,"./parser",setOf(child)))
        denied{LocalUbuntuGraphqlDataAdmission.resolve(parent,"node:fs",emptySet())}
        denied{LocalUbuntuGraphqlDataAdmission.resolve(parent,"../../outside",setOf("node_modules/outside.js"))}
        denied{LocalUbuntuGraphqlDataAdmission.resolve(parent,"missing-package",emptySet())}
    }
    @Test fun regexAndTemplateTextDoNotInventImportsButExpressionsRemainChecked() {
        assertEquals(setOf("graphql"),LocalUbuntuGraphqlDataAdmission.literalRequires("const pattern=/require\\(['\"]fake['\"]\\)/g; const t=`raw require('fake') ${'$'}{require('graphql')}`;"))
        denied{LocalUbuntuGraphqlDataAdmission.literalRequires("const t=`${'$'}{require(name)}`;")}
        denied{LocalUbuntuGraphqlDataAdmission.literalRequires("const t=`${'$'}{import('other')}`;")}
    }
    @Test fun onlyReviewedTypesUmdMayPassItsLoaderParameter() {
        val source="(function(factory){ if(typeof module === 'object' && typeof module.exports === 'object'){ factory(require,exports); }})(function(require,exports){exports.value=1;});"
        denied{LocalUbuntuGraphqlDataAdmission.literalRequires(source)}
        assertTrue(LocalUbuntuGraphqlDataAdmission.literalRequires(source,true).isEmpty())
        denied{LocalUbuntuGraphqlDataAdmission.literalRequires(source+"const alias=require;",true)}
    }
}
