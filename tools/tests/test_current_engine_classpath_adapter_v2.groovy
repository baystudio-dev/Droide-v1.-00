// Evaluate the actual Gradle adapter with a bounded fake task/artifact-view boundary; no Gradle task runs.
import java.util.zip.*
import java.nio.file.Files
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
int passed=0
def zip={Map members ->def out=new ByteArrayOutputStream();new ZipOutputStream(out).withCloseable {z->members.each {n,b->z.putNextEntry(new ZipEntry(n));z.write(b as byte[]);z.closeEntry()}};out.toByteArray()}
def fixture=(0..<40).collectEntries {[("com/termux/terminal/C${it}.class".toString()):("class${it}".bytes)]}
def runCase={String label, String mutation, boolean accepted ->
 def root=Files.createTempDirectory('engine-adapter-').toFile()
 try {
  def engine=new File(root,'engine');engine.mkdirs();def aar=new File(engine,'terminal-engine.aar');aar.bytes=zip(['classes.jar':zip(fixture)])
  def classes=new File(root,'classes.jar');classes.bytes=zip(fixture)
  def resources=new File(root,'resources.jar');resources.bytes=zip(mutation=='resource'?fixture+['x.txt':'resource'.bytes]:fixture)
  def viewFixture=(0..<19).collectEntries {[("com/termux/view/V${it}.class".toString()):("view${it}".bytes)]}
  if(mutation=='overlap')viewFixture=fixture
  def libs=new File(root,'libs');libs.mkdirs();def viewAar=new File(libs,'terminal-view-v0.118.0.aar');viewAar.bytes=zip(['classes.jar':zip(viewFixture)])
  def viewClasses=new File(root,'view-classes.jar');viewClasses.bytes=zip(viewFixture)
  def viewResources=new File(root,'view-resources.jar');viewResources.bytes=zip(mutation=='view-resource'?viewFixture+['x.txt':'resource'.bytes]:viewFixture)
  def hash={File f->MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex().toString()}
  new File(root,'current-resolved-inputs-before.json').text=JsonOutput.toJson([debugRuntimeClasspath:[[filePath:aar.absolutePath,sha256:mutation=='wrong-hash'?'bad':hash(aar)],[filePath:viewAar.absolutePath,sha256:hash(viewAar)]]])
  def component=[displayName:'selected'];def viewComponent=[displayName:'view'];def foreign=[displayName:'foreign']
  def attrs=new Expando(keySet:{[] as Set})
  def artifact={File f,Object id ->[file:f,id:[componentIdentifier:id],variant:[attributes:attrs]]}
  def rMap=['com/termux/terminal/R.class':'r-terminal'.bytes,'com/termux/view/R.class':'r-view'.bytes]
  def generatedR=new File(root,'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar');generatedR.parentFile.mkdirs();generatedR.bytes=zip(mutation=='r-resource'?rMap+['x.txt':'extra'.bytes]:rMap)
  def runtimeJar=new File(root,'intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar');runtimeJar.parentFile.mkdirs();runtimeJar.bytes=zip(rMap+['com/example/Main.class':'main'.bytes]+(mutation=='changed-r'?['com/termux/view/R.class':'changed'.bytes]:[:]))
  def cp=[classes,resources,viewClasses,viewResources,generatedR,runtimeJar] as LinkedHashSet
  if(mutation=='third-r'){def extra=new File(root,'third-r.jar');extra.bytes=zip(rMap);cp.add(extra)}
  if(mutation=='third'){def third=new File(root,'third.jar');third.bytes=zip(fixture);cp.add(third)}
  if(mutation=='missing')cp.remove(resources)
  def raw=(mutation=='wrong-aar'?[]:[artifact(aar,component)])+[artifact(viewAar,viewComponent)]
  def incoming=new Expando();incoming.artifacts=[artifacts:raw]
  incoming.artifactView={Closure request ->
   def wanted=null;def filter=null;def builder=new Expando()
   builder.componentFilter={Closure predicate ->filter=predicate}
   builder.attributes={Closure attributeBlock ->
    def attr=new Expando();attr.attribute={a,b->wanted=b};attributeBlock.delegate=attr;attributeBlock.resolveStrategy=Closure.DELEGATE_FIRST;attributeBlock.call()
   }
   request.delegate=builder;request.resolveStrategy=Closure.DELEGATE_FIRST;request.call()
   boolean isEngine=filter(component);assert isEngine!=filter(viewComponent)
   def id=isEngine?component:viewComponent
   def value=wanted=='android-classes-jar'?artifact(isEngine?classes:viewClasses,id):artifact(isEngine?resources:viewResources,mutation=='wrong-component'?foreign:id)
   [artifacts:[artifacts:mutation=='ambiguous'?[value,value]:[value]]]
  }
  Closure action=null;def task=new Expando(classpath:[files:cp]);task.doFirst={Closure c->action=c}
  def applied=[];def binding=new Binding([
   gradle:[startParameter:[projectProperties:[DROIDE_CURRENT_COMPOSITE_V2:'true',DROIDE_CURRENT_COMPOSITE:root.absolutePath]]],
   rootProject:[file:{String p->new File(args[0],p)}],
   file:{String p->new File(root,p)},
   apply:{Map value->applied+=value.from.name},
   evaluate:{File file->new GroovyShell().evaluate(file)},
   afterEvaluate:{Closure c->c.call()},
   tasks:[named:{String name,Class type=null->
    if(name=='testDebugUnitTest')return [configure:{Closure c->c.delegate=task;c.resolveStrategy=Closure.DELEGATE_FIRST;c.call(task)}]
    def output=name=='processDebugResources'?generatedR:runtimeJar
    if(mutation=='wrong-r-owner')output=new File(root,'wrong-owner')
    new Expando(get:{->[outputs:[files:[files:[output] as Set]]]})
   }],
   configurations:[getByName:{String name->assert name=='debugUnitTestRuntimeClasspath';[incoming:incoming]}],
   layout:[buildDirectory:new Expando(file:{String path->new Expando(get:{->[asFile:new File(root,path)]})},get:{->[asFile:root]})],
   files:{List paths->[files:paths.collect{new File(it)} as LinkedHashSet]}
  ])
  ['gradle','configurations','layout','files','file','tasks'].each { task.setProperty(it,binding.getVariable(it)) }
  new GroovyShell(binding).evaluate(new File(args[0],'tools/current-app-runtime-contracts-v2.gradle'))
  assert applied==['current-app-runtime-contracts-v2-base.gradle'];assert action!=null
  boolean refused=false
  try {action.delegate=task;action.resolveStrategy=Closure.DELEGATE_FIRST;action.call()}catch(IllegalArgumentException|org.gradle.api.GradleException failure){refused=true}
  assert refused!=accepted
  if(accepted){assert task.classpath.files==[classes,viewClasses,runtimeJar] as Set;def report=new JsonSlurper().parse(new File(root,'current-runtime-engine-classpath-v2.json'));assert report.original.size()==6;assert report.effective.size()==3;assert report.generatedR.rMembers.size()==2;assert report.components.size()==2;report.components.each {assert it.selectedEvidence.aarSha256.size()==64;assert it.selectedEvidence.classesJarSha256.size()==64}}
  else {assert task.classpath.files==cp;assert !new File(root,'current-runtime-engine-classpath-v2.json').exists()}
  passed++;println('PASS '+label)
 } finally {root.deleteDir()}
}
runCase('actual adapter retains only classes view','none',true)
runCase('wrong AAR component lookup refuses','wrong-aar',false)
runCase('foreign view component refuses','wrong-component',false)
runCase('ambiguous view refuses','ambiguous',false)
runCase('missing classpath artifact refuses','missing',false)
runCase('resource-bearing redundant artifact refuses','resource',false)
runCase('third engine provider refuses before mutation','third',false)
runCase('view resource refuses before either removal','view-resource',false)
runCase('cross-component overlap refuses','overlap',false)
runCase('prior resolved AAR hash mismatch refuses','wrong-hash',false)
runCase('generated R resource refuses','r-resource',false)
runCase('generated R changed subset refuses','changed-r',false)
runCase('generated R wrong task owner refuses','wrong-r-owner',false)
runCase('third generated R provider refuses','third-r',false)
println("${passed} actual adapter boundary tests passed")
