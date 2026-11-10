package com.baystudio.droide.core
import org.junit.Test
import org.junit.Assert.*
class LspCompletionIdentityTest {
 @Test fun sameLabelDifferentImportsRemainSelectable() {
  val a=LspCompletionItem("Widget","Widget",detail="module A",additionalTextEdits=listOf(TextRangeEdit(1,1,1,1,"import a.Widget\n")))
  val b=a.copy(detail="module B",additionalTextEdits=listOf(TextRangeEdit(1,1,1,1,"import b.Widget\n")))
  assertEquals(listOf(a,b),ProfessionalCompletion.filterLsp(listOf(a,b),"Wid"))
 }
 @Test fun rankingCannotMapPreferredItemToFirstDifferentEdit() {
  val a=LspCompletionItem("Widget","Widget",sortText="z",editStartLine=1,editStartColumn=1,editEndLine=1,editEndColumn=4)
  val b=a.copy(sortText="a",editEndColumn=8)
  assertSame(b,ProfessionalCompletion.filterLsp(listOf(a,b),"Wid").first())
 }
 @Test fun plainExactDuplicatesStillCollapse() {
  val a=LspCompletionItem("Widget","Widget")
  assertEquals(listOf(a),ProfessionalCompletion.filterLsp(listOf(a,a.copy()),"Wid"))
 }
 @Test fun unrelatedPrefixStillFilters() {
  assertTrue(ProfessionalCompletion.filterLsp(listOf(LspCompletionItem("Widget","Widget")),"zzz").isEmpty())
 }
 @Test fun distinctGuardsRemainAttachedToOriginalItems() {
  val a=LspCompletionItem("Widget","Widget",applicationGuard=LspCompletionApplicationGuard("old") {false})
  val b=a.copy(applicationGuard=LspCompletionApplicationGuard("current") {true})
  val result=ProfessionalCompletion.filterLsp(listOf(a,b),"Wid")
  assertEquals(2,result.size);assertSame(a,result[0]);assertSame(b,result[1])
 }
 @Test fun nativeIdentitiesAreNotCollapsed() {
  val a=LspCompletionItem("Widget","Widget",nativeOrigin="provider",nativeBatch=1,nativeItemIdentity="moduleA")
  val b=a.copy(nativeItemIdentity="moduleB")
  assertEquals(listOf(a,b),ProfessionalCompletion.filterLsp(listOf(a,b),"Wid"))
 }
 @Test fun existingRankComparatorAndLimitRemainStable() {
  val items=(0..150).map {LspCompletionItem("Widget$it","Widget$it",sortText=it.toString().padStart(3,'0'))}.reversed()
  val expected=ProfessionalCompletion.rank(ProfessionalCompletion.fromLsp(items),"Wid",ProfessionalCompletion.MAX_LSP_ITEMS).map {it.label}
  val result=ProfessionalCompletion.filterLsp(items,"Wid")
  assertEquals(expected,result.map {it.label});assertEquals(120,result.size)
 }
}
