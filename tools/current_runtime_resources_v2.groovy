// AGP 8.13 emits module-relative test config paths. Resolve only owned task outputs.
import java.nio.file.Files
import java.nio.file.LinkOption

def plain={ File input ->
    def path=input.toPath().toAbsolutePath().normalize()
    for(def p=path;p!=null;p=p.parent)if(Files.isSymbolicLink(p))throw new IllegalArgumentException('Linked runtime resource')
    path
}
def resolve={File module,String raw,File expected,boolean directory,Collection<File> ownerOutputs ->
    if(!raw || new File(raw).isAbsolute() || raw.contains('\\'))throw new IllegalArgumentException('Expected module-relative resource')
    def relative=new File(raw).toPath()
    if(relative.any{it.toString() in ['..','.']} || relative.normalize()!=relative)throw new IllegalArgumentException('Noncanonical resource path')
    def root=plain(module);def actual=plain(new File(module,raw));def target=plain(expected)
    if(!actual.startsWith(root) || actual==root || actual!=target)throw new IllegalArgumentException('Wrong owned resource path')
    if(directory?!Files.isDirectory(actual,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(actual,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException('Missing/wrong-type runtime resource')
    boolean owned=ownerOutputs.any{File output->def p=plain(output);actual==p || (Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) && actual.startsWith(p))}
    if(!owned)throw new IllegalArgumentException('Resource not owned by expected AGP task')
    actual.toFile()
}
def workingDirectory={File module,File working ->
    if(plain(module)!=plain(working))throw new IllegalArgumentException('Test working directory differs from AGP module')
}
return [resolve:resolve,workingDirectory:workingDirectory]
