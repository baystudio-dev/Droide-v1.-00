import java.nio.file.Files

def policy=evaluate(new File(args[0]));int passed=0
def check={String name,Closure c->c();passed++;println('PASS '+name)}
def bad={Closure c->boolean rejected=false;try{c()}catch(IllegalArgumentException e){rejected=true};assert rejected}
def root=Files.createTempDirectory('runtime-resource-owner-').toFile()
try{
 def build=new File(root,'build');build.mkdirs();def manifest=new File(build,'manifest.xml');manifest.text='synthetic'
 def assets=new File(build,'assets');assets.mkdirs()
 check('owned file'){assert policy.resolve(root,'build/manifest.xml',manifest,false,[build])==manifest}
 check('owned assets directory'){assert policy.resolve(root,'build/assets',assets,true,[assets])==assets}
 check('same working owner'){policy.workingDirectory(root,root)}
 check('wrong working owner'){bad{policy.workingDirectory(root,build)}}
 check('missing value'){bad{policy.resolve(root,null,manifest,false,[build])}}
 check('absolute'){bad{policy.resolve(root,manifest.absolutePath,manifest,false,[build])}}
 check('parent escape'){bad{policy.resolve(root,'../foreign',manifest,false,[build])}}
 check('dot alias'){bad{policy.resolve(root,'build/./manifest.xml',manifest,false,[build])}}
 check('backslash'){bad{policy.resolve(root,'build\\manifest.xml',manifest,false,[build])}}
 check('wrong task owner'){bad{policy.resolve(root,'build/manifest.xml',manifest,false,[assets])}}
 check('wrong exact path'){bad{policy.resolve(root,'build/manifest.xml',new File(build,'other.xml'),false,[build])}}
 check('missing file'){bad{policy.resolve(root,'build/missing',new File(build,'missing'),false,[build])}}
 check('directory as file'){bad{policy.resolve(root,'build/assets',assets,false,[build])}}
 check('file as directory'){bad{policy.resolve(root,'build/manifest.xml',manifest,true,[build])}}
 def link=new File(root,'linked');Files.createSymbolicLink(link.toPath(),build.toPath())
 check('symlink'){bad{policy.resolve(root,'linked/manifest.xml',new File(link,'manifest.xml'),false,[build])}}
}finally{root.deleteDir()}
println("${passed} pure resource resolver checks passed")
