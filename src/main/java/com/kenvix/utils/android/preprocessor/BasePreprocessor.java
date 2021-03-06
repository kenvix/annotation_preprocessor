package com.kenvix.utils.android.preprocessor;

import com.kenvix.utils.android.annotation.ErrorPrompt;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

public abstract class BasePreprocessor extends AbstractProcessor {
    protected Types typeUtil;
    protected Elements elementUtil;
    protected Filer filer;
    protected Messager messager;
    protected ProcessingEnvironment processingEnv;

    private static Map<Class, Map<String, List<MethodSpec.Builder>>> globalMethodBuffer = new HashMap<>();

    {
        if(!globalMethodBuffer.containsKey(this.getClass()))
            globalMethodBuffer.put(this.getClass(), new HashMap<>());
    }


    public String getTargetAppPackage() {
        if (processingEnv.getOptions().get("TargetAppPackage") == null)
            throw new IllegalArgumentException("TargetAppPackage cannot be null. Edit build.gradle and add kapt arguments like: " +
                    "kapt {\n" +
                    "    arguments {\n" +
                    "        arg(\"TargetAppPackage\", android.defaultConfig.applicationId)\n" +
                    "        arg(\"ExtendedProcessPackages\", \"\")\n" +
                    "    }\n" +
                    "}");

        return processingEnv.getOptions().get("TargetAppPackage");
    }

    public List<String> getExtendedProcessPackages() {
        String packagesStr = processingEnv.getOptions().get("ExtendedProcessPackages");
        String[] array = packagesStr == null ? null : packagesStr.split(",");

        if (array == null) {
            return new ArrayList<String>(1) {{
                add("com.kenvix.android");
            }};
        } else {
            List<String> list = Arrays.asList(array);
            list.add("com.kenvix.android");

            return list;
        }
    }

    protected String getFileHeader() {
        return "This file is generated by " +
                this.getClass().getSimpleName() +
                "\nDo NOT modify this file!\n-------------------------------------\nCopyright (c) 2019 Kenvix <i@kenvix.com>. All rights reserved.";
    }

    protected Map<String, List<MethodSpec.Builder>> getMethodBuffer() {
        return globalMethodBuffer.get(this.getClass());
    }

    protected final List<MethodSpec.Builder> getMethodBuilder(String methodName) {
        return getMethodBuilder(methodName, null);
    }

    protected final List<MethodSpec.Builder> getMethodBuilder(String methodName, Element clazz) {
        Map<String, List<MethodSpec.Builder>> methodBuffer = getMethodBuffer();

        if(methodBuffer.containsKey(methodName))
            return methodBuffer.get(methodName);

        synchronized (this) {
            if(methodBuffer.containsKey(methodName))
                return methodBuffer.get(methodName);

            final List<MethodSpec.Builder> methodSpec;

            if(clazz == null)
                methodSpec = createMethodBuilder(methodName);
            else
                methodSpec = createMethodBuilder(methodName, clazz);

            if(methodSpec == null)
                throw new IllegalArgumentException("Generate method code failed: create method for tag [" + methodName + "] is not implemented");

            methodBuffer.put(methodName, methodSpec);
            return methodSpec;
        }
    }

    protected List<MethodSpec.Builder> createMethodBuilder(String methodName) { return null; }
    protected List<MethodSpec.Builder> createMethodBuilder(String methodName, Element clazz) { return null; }

    protected abstract boolean onProcess(Map<Element, List<Element>> filteredAnnotations, Set<? extends TypeElement> originalAnnotations, RoundEnvironment roundEnv);
    protected abstract Class[] getSupportedAnnotations();
    protected abstract boolean onProcessingOver(Map<Element, List<Element>> filteredAnnotations, Set<? extends TypeElement> originalAnnotations, RoundEnvironment roundEnv);

    @Override
    public synchronized final void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processingEnv = processingEnv;
        typeUtil = processingEnv.getTypeUtils();
        elementUtil = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        onPreprocessorInit();

        messager.printMessage(Diagnostic.Kind.NOTE, "Annotation Preprocessor: " + this.getClass().getSimpleName() + " Initialized");
    }

    protected void onPreprocessorInit() {}

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> rootElements = roundEnv.getRootElements();
        Map<Element, List<Element>> tasks = new HashMap<>();
        Class[] supportedAnnotations = getSupportedAnnotations();

        for (Element classElement : rootElements) {
            if(shouldProcess(classElement.toString())) {
                List<? extends Element> enclosedElements = classElement.getEnclosedElements();

                for(Element enclosedElement : enclosedElements) {
                    List<? extends AnnotationMirror> annotationMirrors = enclosedElement.getAnnotationMirrors();

                    for (AnnotationMirror annotationMirror : annotationMirrors) {

                        for(Class supportedAnnotation : supportedAnnotations) {
                            if(supportedAnnotation.getName().equals(annotationMirror.getAnnotationType().toString())) {
                                if(!tasks.containsKey(classElement))
                                    tasks.put(classElement, new LinkedList<>());

                                tasks.get(classElement).add(enclosedElement);
                            }
                        }
                    }
                }
            }
        }

        return onProcess(tasks, annotations, roundEnv) && (!roundEnv.processingOver() || onProcessingOver(tasks, annotations, roundEnv));
    }

    protected boolean shouldProcess(String packagePrefix) {
        if (packagePrefix.startsWith(getTargetAppPackage()))
            return true;

        for (String prefix : getExtendedProcessPackages()) {
            if (packagePrefix.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public final Set<String> getSupportedAnnotationTypes() {
        //Arrays.stream(getSupportedAnnotations()).forEach(annotationClass -> messager.printMessage(Diagnostic.Kind.NOTE, annotationClass.getCanonicalName()));

        return new LinkedHashSet<String>() {{
            Arrays.stream(getSupportedAnnotations()).forEach(annotationClass -> add(annotationClass.getCanonicalName()));
        }};
    }

    protected ClassName getTargetClassName(Element clazz) {
        String fullName = clazz.toString();
        return ClassName.get(fullName.substring(0, fullName.indexOf(clazz.getSimpleName().toString())-1), clazz.getSimpleName().toString());
    }

    protected final JavaFile.Builder getOutputJavaFileBuilder(TypeSpec className) {
        return JavaFile.builder(getTargetAppPackage() + ".generated", className)
                .addFileComment(getFileHeader());
    }

    protected final String getErrorPromptStatement(Element annotatedElement, String defaultValue) {
        ErrorPrompt errorPrompt = annotatedElement.getAnnotation(ErrorPrompt.class);

        if (errorPrompt == null)
            return defaultValue;
        else
            return errorPrompt.value();
    }


    /**
     *
     * fuck idea error prompt bug
     * @param javaFile
     */
    protected final void saveOutputJavaFile(JavaFile javaFile) throws IOException {
        messager.printMessage(Diagnostic.Kind.NOTE, "Annotation Preprocessor: " + this.getClass().getSimpleName() + " saved output file:" + javaFile.packageName);
        javaFile.writeTo(filer);
    }
}
