import java.util.zip.*
def policy=evaluate(new File(args[0]))
int passed=0
def test={String name,Closure body ->body();passed++;println('PASS '+name)}
def rejects={Closure body ->boolean refused=false;try{body()}catch(IllegalArgumentException e){refused=true};assert refused}
def zip={Map members ->def out=new ByteArrayOutputStream();new ZipOutputStream(out).withCloseable {z->members.each {n,b->z.putNextEntry(new ZipEntry(n));z.write(b as byte[]);z.closeEntry()}};out.toByteArray()}
def fixture=(0..<40).collectEntries {[("com/termux/terminal/C${it}.class".toString()):("class${it}".bytes)]}
def bytes=zip(fixture);def expected=policy.entries(bytes)
def c=[path:'classes',component:'selected',members:expected];def r=[path:'resources',component:'selected',members:expected]
test('exact pair retains classes only'){assert policy.select(expected,['classes','resources','other'],c,r,'selected').effective==['classes','other']}
test('wrong component'){rejects{policy.select(expected,['classes','resources'],c,r+[component:'foreign'],'selected')}}
test('missing component'){rejects{policy.select(expected,['classes','resources'],c,r,null)}}
test('missing view'){rejects{policy.select(expected,['classes'],c,r,'selected')}}
test('duplicate path'){rejects{policy.select(expected,['classes','resources','resources'],c,r,'selected')}}
test('same path ambiguity'){rejects{policy.select(expected,['classes'],c,r+[path:'classes'],'selected')}}
test('altered class'){rejects{policy.select(expected,['classes','resources'],c,r+[members:expected+['com/termux/terminal/C0.class':'bad']],'selected')}}
test('missing class'){def fewer=new TreeMap(expected);fewer.remove('com/termux/terminal/C0.class');rejects{policy.select(expected,['classes','resources'],c,r+[members:fewer],'selected')}}
test('resource cannot be discarded'){rejects{policy.entries(zip(fixture+['config.properties':'secret'.bytes]))}}
test('unsafe path'){rejects{policy.entries(zip(['../x.class':[1] as byte[]]))}}
test('empty archive'){rejects{policy.entries(zip([:]))}}
test('oversized class'){rejects{policy.entries(zip(['x.class':new byte[4*1024*1024+1]]))}}
test('excess members'){rejects{policy.entries(zip((0..<129).collectEntries{[("x${it}.class".toString()):[1] as byte[]]}))}}
test('duplicate zip member'){def b=zip(['a.class':[1] as byte[],'b.class':[2] as byte[]]);for(int i=0;i<b.length-7;i++)if(new String(b,i,7,'ISO-8859-1')=='b.class')b[i]=(byte)'a'.charAt(0);rejects{policy.entries(b)}}
test('actual file bounded reader'){def f=File.createTempFile('engine-policy-','.jar');try{f.bytes=bytes;assert policy.entries(policy.regular(f))==expected}finally{f.delete()}}
test('third provider'){rejects{policy.select(expected,['classes','resources','third'],c,r,'selected',['third'])}}
test('wrong selected map'){def wrong=new TreeMap(expected);wrong['com/termux/terminal/C0.class']='bad';rejects{policy.select(wrong,['classes','resources'],c,r,'selected')}}
test('selected AAR exact nested map'){def f=File.createTempFile('selected-engine-','.aar');try{f.bytes=zip(['classes.jar':bytes]);assert policy.selectedClasses(f)==expected}finally{f.delete()}}
test('selected AAR missing classes'){def f=File.createTempFile('selected-engine-','.aar');try{f.bytes=zip(['other.jar':bytes]);rejects{policy.selectedClasses(f)}}finally{f.delete()}}
test('exact view shape'){def expectedView=(0..<19).collectEntries{[("com/termux/view/V${it}.class".toString()):'hash']};assert policy.select(expectedView,['c','r'],[path:'c',component:'v',members:expectedView],[path:'r',component:'v',members:expectedView],'v',[],[count:19,prefix:'com/termux/view/']).effective==['c']}
test('cross-component namespace rejected'){rejects{policy.select(expected,['classes','resources'],c,r,'selected',[],[count:19,prefix:'com/termux/view/'])}}
test('arbitrary shape refused'){rejects{policy.select(expected,['classes','resources'],c,r,'selected',[],[count:40,prefix:'com/termux/'])}}
def rFixture={Closure body ->
 def build=java.nio.file.Files.createTempDirectory('rFile-policy-').toFile()
 try {
  def rFile=new File(build,'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar');rFile.parentFile.mkdirs()
  def runtime=new File(build,'intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar');runtime.parentFile.mkdirs()
  rFile.bytes=zip(['a/R.class':'rFile'.bytes]);runtime.bytes=zip(['a/R.class':'rFile'.bytes,'a/Main.class':'main'.bytes])
  body(build,rFile,runtime,[rFile.absolutePath,runtime.absolutePath])
 }finally{build.deleteDir()}
}
test('exact R subset retains runtime'){rFixture{b,rFile,j,cp->assert policy.rSubset(b,rFile,j,cp,[rFile],[j]).retained==j.absolutePath}}
test('non-R generated entry refuses'){rFixture{b,rFile,j,cp->rFile.bytes=zip(['a/Main.class':'main'.bytes]);rejects{policy.rSubset(b,rFile,j,cp,[rFile],[j])}}}
test('missing generated class refuses'){rFixture{b,rFile,j,cp->j.bytes=zip(['a/Main.class':'main'.bytes]);rejects{policy.rSubset(b,rFile,j,cp,[rFile],[j])}}}
test('wrong generated path refuses'){rFixture{b,rFile,j,cp->rejects{policy.rSubset(b,new File(b,'other.jar'),j,cp,[rFile],[j])}}}
test('empty R map refuses'){rFixture{b,rFile,j,cp->rFile.bytes=zip([:]);rejects{policy.rSubset(b,rFile,j,cp,[rFile],[j])}}}
println("${passed} policy tests passed")
