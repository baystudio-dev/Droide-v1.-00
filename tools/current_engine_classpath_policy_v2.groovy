// Shared bounded policy. No Gradle mutation, artifact resolution, or class loading.
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

def require = { boolean ok, String message -> if (!ok) throw new IllegalArgumentException(message) }
def digest = { byte[] data -> MessageDigest.getInstance('SHA-256').digest(data).encodeHex().toString() }
def regular = { File file, long maximum = 64L*1024*1024 ->
    for (def p=file.toPath();p!=null;p=p.parent) require(!Files.isSymbolicLink(p),'Linked classpath input')
    require(file.isFile() && file.length() <= maximum,'Missing/oversized engine input')
    long expected=file.length();def out=new ByteArrayOutputStream()
    Files.newInputStream(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).withCloseable { stream ->
        byte[] buffer=new byte[8192];int n
        while((n=stream.read(buffer))!=-1) {require(out.size()+n<=expected,'Engine input grew');out.write(buffer,0,n)}
    }
    require(out.size()==expected && file.length()==expected,'Engine input changed');out.toByteArray()
}
def entries = { byte[] bytes ->
    require(bytes.length <= 64L*1024*1024,'Oversized archive')
    def result=new TreeMap<String,String>();def seen=[] as Set;long total=0
    new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zip ->
        for(def entry=zip.nextEntry;entry!=null;entry=zip.nextEntry) {
            def name=entry.name
            require(seen.add(name) && seen.size() <= 128,'Duplicate/excess archive member')
            require(!name.startsWith('/') && !name.contains('\\') && !name.split('/').any { it in ['.','..',''] },'Unsafe archive member')
            if(entry.directory) continue
            require(name.endsWith('.class'),'Engine resource cannot be discarded')
            def output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count
            while((count=zip.read(buffer))!=-1) {
                total+=count;require(total<=16L*1024*1024 && output.size()+count<=4L*1024*1024,'Oversized class data')
                output.write(buffer,0,count)
            }
            result[name]=digest(output.toByteArray())
        }
    }
    require(!result.isEmpty(),'Empty engine archive');result
}
def select = { Map expected, List classpath, Map classes, Map resources, Object component, List otherProviders = [], Map shape = [count:40,prefix:'com/termux/terminal/'] ->
    require(otherProviders.isEmpty(),'Third engine provider is forbidden')
    require(component!=null && classes.component==component && resources.component==component,'Wrong engine component')
    require(classes.path!=resources.path && classpath.count(classes.path)==1 && classpath.count(resources.path)==1,'Missing/ambiguous selected paths')
    require(classes.members==expected && resources.members==expected,'Engine entries differ from selected AAR')
    require(shape in [[count:40,prefix:'com/termux/terminal/'],[count:19,prefix:'com/termux/view/']],'Unselected Termux component shape')
    require(expected.size()==shape.count && expected.keySet().every {it.startsWith(shape.prefix) && it.endsWith('.class')},'Selected engine shape changed')
    [removed:resources.path,retained:classes.path,effective:classpath.findAll {it!=resources.path}]
}
def selectedEvidence = { File file ->
    byte[] captured=regular(file);byte[] classes=null;def seen=[] as Set;long total=0
    new ZipInputStream(new ByteArrayInputStream(captured)).withCloseable { archive ->
        for(def entry=archive.nextEntry;entry!=null;entry=archive.nextEntry) {
            require(seen.add(entry.name) && seen.size()<=1024,'Duplicate/excess AAR member')
            def out=entry.name=='classes.jar'?new ByteArrayOutputStream():null
            require(out==null || !entry.directory,'Selected classes.jar is a directory')
            byte[] buffer=new byte[8192];int n
            while((n=archive.read(buffer))!=-1) {
                total+=n;require(total<=64L*1024*1024,'Oversized expanded AAR')
                if(out!=null){require(out.size()+n<=16L*1024*1024,'Oversized selected classes.jar');out.write(buffer,0,n)}
            }
            if(out!=null) classes=out.toByteArray()
        }
    }
    require(classes!=null,'Missing selected classes.jar')
    [aarSha256:digest(captured),classesJarSha256:digest(classes),members:entries(classes)]
}
def inventory = {File file ->
    byte[] captured=regular(file,128L*1024*1024);def values=new TreeMap();def seen=[] as Set;long total=0
    new ZipInputStream(new ByteArrayInputStream(captured)).withCloseable {zip ->
        for(def entry=zip.nextEntry;entry!=null;entry=zip.nextEntry) {
            def name=entry.name
            require(seen.add(name) && seen.size()<=20000,'Duplicate/excess generated JAR member')
            require(!name.startsWith('/') && !name.contains('\\') && !name.split('/').any {it in ['.','..','']},'Unsafe generated JAR member')
            if(entry.directory)continue
            def hash=MessageDigest.getInstance('SHA-256');long size=0;byte[] buffer=new byte[8192];int n
            while((n=zip.read(buffer))!=-1){size+=n;total+=n;require(size<=8L*1024*1024 && total<=256L*1024*1024,'Oversized generated JAR content');hash.update(buffer,0,n)}
            values[name]=hash.digest().encodeHex().toString()
        }
    }
    [sha256:digest(captured),members:values]
}
def rSubset = {File build, File r, File runtime, List classpath, List rOwners, List runtimeOwners, List otherProviders=[] ->
    def expectedR=new File(build,'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar').absolutePath
    def expectedRuntime=new File(build,'intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar').absolutePath
    require(r.absolutePath==expectedR && runtime.absolutePath==expectedRuntime,'Wrong generated task output path')
    def owned={File target,List owners ->owners.any {File owner -> owner.absolutePath==target.absolutePath || (owner.isDirectory() && target.toPath().startsWith(owner.toPath()))}}
    require(owned(r,rOwners) && owned(runtime,runtimeOwners),'Wrong generated task owner')
    require(classpath.count(r.absolutePath)==1 && classpath.count(runtime.absolutePath)==1 && otherProviders.isEmpty(),'Ambiguous/extra generated R provider')
    def generated=inventory(r);def retained=inventory(runtime)
    require(!generated.members.isEmpty() && generated.members.size()<=4096,'Empty/excess R class map')
    require(generated.members.every {name,hash -> name==~ /(?:[^\/]+\/)*R(?:\$[A-Za-z0-9_]+)?\.class/ && retained.members[name]==hash},'Generated R entries are not an exact class-only subset')
    [removed:r.absolutePath,retained:runtime.absolutePath,rSha256:generated.sha256,runtimeSha256:retained.sha256,rMembers:generated.members,runtimeEntryCount:retained.members.size()]
}
return [regular:regular, entries:entries, select:select, selectedEvidence:selectedEvidence, selectedClasses:{File f -> selectedEvidence(f).members}, rSubset:rSubset]
