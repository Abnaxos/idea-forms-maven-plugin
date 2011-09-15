package ch.raffael.maven.ideaForms;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.intellij.ant.AntClassWriter;
import com.intellij.uiDesigner.compiler.AlienFormFileException;
import com.intellij.uiDesigner.compiler.AsmCodeGenerator;
import com.intellij.uiDesigner.compiler.FormErrorInfo;
import com.intellij.uiDesigner.compiler.NestedFormLoader;
import com.intellij.uiDesigner.compiler.Utils;
import com.intellij.uiDesigner.lw.CompiledClassPropertiesProvider;
import com.intellij.uiDesigner.lw.LwRootContainer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.EmptyVisitor;


/**
 * @goal compile-forms
 * @phase process-classes
 * @requiresDependencyResolution compile
 * @threadSafe true
 *
 * @author <a href="mailto:herzog@raffael.ch">Raffael Herzog</a>
 */
public class FormsCompilerMojo extends AbstractMojo {

    /**
     * @parameter expression="${project}
     * @read-only
     */
    private MavenProject project;

    /**
     * @parameter expression="${project.build.sourceDirectory}
     */
    private File sourceDir;

    /**
     * @parameter expression="${project.build.outputDirectory}
     * @read-only
     */
    private File outputDir;

    /**
     * @parameter
     */
    private String[] includes = { "**/*.form" };

    /**
     * @parameter
     */
    private String[] excludes = null;

    /**
     * @parameter
     */
    private boolean copyFormFiles = true;

    private final Map<String, File> boundClasses = new HashMap<String, File>();

    private boolean hadErrors = false;

    private Map<String, File> formFiles;
    private Map<String, LwRootContainer> formCache = new HashMap<String, LwRootContainer>();
    private CompiledClassPropertiesProvider propertiesProvider;
    private ClassLoader classpathClassLoader;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(includes);
        scanner.setExcludes(excludes);
        scanner.setBasedir(sourceDir);
        scanner.scan();
        String[] includedFiles = scanner.getIncludedFiles();
        if ( includedFiles != null && includedFiles.length > 0 ) {
            classpathClassLoader = null;
            try {
                classpathClassLoader = buildClasspathClassLoader();
            }
            catch ( MalformedURLException e ) {
                throw new MojoExecutionException(e.toString(), e);
            }
            propertiesProvider = new CompiledClassPropertiesProvider(classpathClassLoader);
            formFiles = new HashMap<String, File>(includedFiles.length);
            for ( int i = 0; i < includedFiles.length; i++ ) {
                formFiles.put(includedFiles[i], new File(sourceDir, includedFiles[i]));
            }
            for ( Map.Entry<String, File> formFileEntry:formFiles.entrySet() ) {
                File formFile = formFileEntry.getValue();
                getLog().debug("Processing form: " + formFile);
                try {
                    LwRootContainer rootContainer = Utils.getRootContainer(formFile.toURI().toURL(), propertiesProvider);
                    formCache.put(formFileEntry.getKey(), rootContainer);
                    String classToBind = rootContainer.getClassToBind();
                    if ( classToBind == null ) {
                        getLog().debug("Form not bound, skipping: " + formFile);
                        continue;
                    }
                    if ( boundClasses.containsKey(classToBind) ) {
                        error(formFile, classToBind + " is bound to both " + formFile + " and " + boundClasses.get(classToBind));
                        continue;
                    }
                    boundClasses.put(classToBind, formFile);
                    File classFile = findClassFile(classToBind);
                    if ( classFile == null ) {
                        error(formFile, "Cannot find class file for " + classToBind);
                        continue;
                    }
                    int classfileVersion = getClassFileVersion(classFile);
                    ClassWriter classWriter = new AntClassWriter(getAsmClassWriterFlags(classfileVersion), classpathClassLoader);
                    AsmCodeGenerator generator = new AsmCodeGenerator(rootContainer, classpathClassLoader, new MavenNestedFormLoader(formFile), false, classWriter);
                    generator.patchFile(classFile);
                    for ( FormErrorInfo warning : generator.getWarnings() ) {
                        getLog().warn(formFile + ":");
                        getLog().warn(warning.getErrorMessage());
                    }
                    for ( FormErrorInfo error : generator.getErrors() ) {
                        error(formFile, error.getErrorMessage());
                    }
                    if ( copyFormFiles ) {
                        File dest = new File(outputDir, formFileEntry.getKey());
                        getLog().debug("Copying form file " + formFile + " to " + dest);
                        FileUtils.copyFile(formFile, dest);
                    }
                }
                catch ( AlienFormFileException e ) {
                    getLog().warn("Skipping non-form file: " + formFile, e);
                }
                catch ( Exception e ) {
                    throw new MojoExecutionException("Error processing form file " + formFile, e);
                }
            }
        }
        if ( hadErrors ) {
            throw new MojoFailureException("There were errors processing forms");
        }
    }

    private int getClassFileVersion(File classFile) throws MojoExecutionException {
        InputStream classInputStream = null;
        try {
            classInputStream = new BufferedInputStream(new FileInputStream(classFile));
            ClassReader classReader = new ClassReader(classInputStream);
            final int[] classfileVersion = new int[1];
            classReader.accept(new EmptyVisitor() {
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    classfileVersion[0] = version;
                }
            }, 0);
            return classfileVersion[0];
        }
        catch ( IOException e ) {
            throw new MojoExecutionException("I/O error processing " + classFile, e);
        }
        finally {
            close(classFile, classInputStream);
        }
    }

    private static int getAsmClassWriterFlags(int version) {
        return version >= Opcodes.V1_6 && version != Opcodes.V1_1 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
    }

    private ClassLoader buildClasspathClassLoader() throws MalformedURLException {
        List artifacts = project.getCompileArtifacts();
        List<URL> urls = new ArrayList<URL>(artifacts.size() + 2);
        urls.add(sourceDir.toURI().toURL()); // needed to load nested forms
        urls.add(outputDir.toURI().toURL());
        for ( int i = 0; i < artifacts.size(); i++ ) {
            Artifact artifact = (Artifact)artifacts.get(i);
            urls.add(artifact.getFile().toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[urls.size()]), null);
    }

    private File findClassFile(String className) {
        String fileName = className.replace('.', File.separatorChar);
        File file = new File(outputDir, fileName + ".class");
        while ( !file.isFile() ) {
            int pos = fileName.lastIndexOf(File.separatorChar);
            if ( pos >= 0 ) {
                fileName = fileName.substring(0, pos) + "$" + fileName.substring(pos + 1);
            }
            else {
                return null;
            }
            file = new File(outputDir, fileName + ".class");
        }
        return file;
    }

    private void error(File formFile, String msg) {
        hadErrors = true;
        if ( formFile != null ) {
            getLog().error(formFile.toString() + ":");
        }
        getLog().error(msg);
    }

    private void error(File formFile, Throwable exception) {
        hadErrors = true;
        if ( formFile != null ) {
            getLog().error(formFile.toString() + ":");
        }
        getLog().error(exception);
    }

    private void error(File formFile, String msg, Throwable exception) {
        hadErrors = true;
        if ( formFile != null ) {
            getLog().error(formFile.toString() + ":");
        }
        getLog().error(msg, exception);
    }

    private void close(Object location, Closeable closeable) {
        if ( closeable != null ) {
            try {
                closeable.close();
            }
            catch ( Exception e ) {
                getLog().warn("Error closing " + String.valueOf(location));
            }
        }
    }

    private class MavenNestedFormLoader implements NestedFormLoader {
        private final File rootFormFile;
        private final HashMap<String, LwRootContainer> formCache = new HashMap<String, LwRootContainer>();

        public MavenNestedFormLoader(File rootFormFile) {
            this.rootFormFile = rootFormFile;
        }

        public LwRootContainer loadForm(String formFilePath) throws Exception {
            LwRootContainer rootContainer = formCache.get(formFilePath);
            if ( rootContainer != null ) {
                return rootContainer;
            }
            File formFile = formFiles.get(formFilePath);
            if ( formFile != null ) {
                rootContainer = Utils.getRootContainer(formFile.toURI().toURL(), propertiesProvider);
            }
            else {
                URL url = classpathClassLoader.getResource("/" + formFilePath);
                if ( url != null ) {
                    rootContainer = Utils.getRootContainer(url, propertiesProvider);
                }
            }
            if ( rootContainer != null ) {
                formCache.put(formFilePath, rootContainer);
            }
            return rootContainer;
        }

        public String getClassToBindName(LwRootContainer container) {
            String className = container.getClassToBind().replace('.', '/');
            // check for inner classes
            while ( classpathClassLoader.getResource(className + ".class") == null ) {
                int pos = className.lastIndexOf('/');
                if ( pos < 0 ) {
                    error(rootFormFile, "Cannot find class to bind in nested form: " + container.getClassToBind());
                    return container.getClassToBind();
                }
                className = className.substring(0, pos) + "$" + className.substring(pos + 1);
            }
            return className.replace('/', '.');
        }
    }

}
