/*
 * The MIT License
 *
 * Copyright 2016 rmpestano.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.cukedoctor.jenkins;

import static com.github.cukedoctor.util.Assert.hasText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.cukedoctor.config.GlobalConfig;
import org.apache.commons.io.IOUtils;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import com.github.cukedoctor.Cukedoctor;
import com.github.cukedoctor.api.CukedoctorConverter;
import com.github.cukedoctor.api.DocumentAttributes;
import com.github.cukedoctor.api.model.Feature;
import com.github.cukedoctor.extension.CukedoctorExtensionRegistry;
import com.github.cukedoctor.jenkins.model.FormatType;
import com.github.cukedoctor.jenkins.model.TocType;
import com.github.cukedoctor.parser.FeatureParser;
import com.github.cukedoctor.util.FileUtil;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;

/**
 * @author rmpestano
 */
public class CukedoctorPublisher extends Recorder implements SimpleBuildStep {

    private String featuresDir;

    private  boolean numbered;

    private  boolean sectAnchors;

    private TocType toc;

    private FormatType format;

    private String title;

    private boolean hideFeaturesSection;

    private boolean hideSummary;

    private boolean hideScenarioKeyword;

    private boolean hideStepTime;

    private boolean hideTags;

    private PrintStream logger;


    @DataBoundConstructor
    public CukedoctorPublisher(String featuresDir, FormatType format, TocType toc, Boolean numbered, Boolean sectAnchors, String title, boolean hideFeaturesSection, boolean hideSummary,
                               boolean hideScenarioKeyword, boolean hideStepTime, boolean hideTags) {
        this.featuresDir = featuresDir;
        this.numbered = numbered == null ? Boolean.TRUE : numbered;
        this.toc = toc == null ? TocType.RIGHT : toc;
        this.format = format == null ? FormatType.HTML : format;
        this.sectAnchors = sectAnchors == null ? Boolean.TRUE : sectAnchors;
        this.title = hasText(title) ? title : "Living Documentation";
        this.hideFeaturesSection = hideFeaturesSection;
        this.hideSummary = hideSummary;
        this.hideScenarioKeyword = hideScenarioKeyword;
        this.hideStepTime = hideStepTime;
        this.hideTags = hideTags;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
    	  
    	 FilePath workspaceJsonSourceDir;//most of the time on slave
    	 FilePath workspaceJsonTargetDir;//always on master
         if (!hasText(featuresDir)) {
             workspaceJsonSourceDir = workspace;
             workspaceJsonTargetDir = getMasterWorkspaceDir(build);
         } else {
             workspaceJsonSourceDir = new FilePath(workspace, featuresDir);
             workspaceJsonTargetDir = new FilePath(getMasterWorkspaceDir(build), featuresDir);
         }
    	
      
        logger = listener.getLogger();
        workspaceJsonSourceDir.copyRecursiveTo("**/*.json,**/cukedoctor-intro.adoc,**/cukedoctor.properties,**/cukedoctor.css,**/cukedoctor-pdf.yml", workspaceJsonTargetDir);
        
        System.setProperty("INTRO_CHAPTER_DIR",workspaceJsonTargetDir.getRemote());
        System.setProperty("CUKEDOCTOR_CUSTOMIZATION_DIR",workspaceJsonTargetDir.getRemote());

        logger.println("");
        logger.println("Generating living documentation for " + build.getFullDisplayName() + " with the following arguments: ");
        logger.println("Features dir: " + workspaceJsonSourceDir.getRemote());
        logger.println("Format: " + format.getFormat());
        logger.println("Toc: " + toc.getToc());
        logger.println("Title: " + title);
        logger.println("Numbered: " + Boolean.toString(numbered));
        logger.println("Section anchors: " + Boolean.toString(sectAnchors));
        logger.println("Hide features section: " + Boolean.toString(hideFeaturesSection));
        logger.println("Hide summary: " + Boolean.toString(hideSummary));
        logger.println("Hide scenario keyword: " + Boolean.toString(hideScenarioKeyword));
        logger.println("Hide step time: " + Boolean.toString(hideStepTime));
        logger.println("Hide tags: " + Boolean.toString(hideTags));
        logger.println("");

        Result result = Result.SUCCESS;
        List<Feature> features = FeatureParser.findAndParse(workspaceJsonTargetDir.getRemote());
        if (!features.isEmpty()) {
            if (!hasText(title)) {
                title = "Living Documentation";
            }

            logger.println("Found " + features.size() + " feature(s)...");

            File targetBuildDirectory = new File(build.getRootDir(), CukedoctorBaseAction.BASE_URL);
            if (!targetBuildDirectory.exists()) {
                boolean created = targetBuildDirectory.mkdirs();
                if (!created) {
                    listener.error("Could not create file at location: " + targetBuildDirectory.getAbsolutePath());
                    result = Result.UNSTABLE;
                }
            }

            GlobalConfig globalConfig = GlobalConfig.getInstance();
            DocumentAttributes documentAttributes = globalConfig.getDocumentAttributes().
                    backend(format.getFormat()).
                    toc(toc.getToc()).
                    numbered(numbered).
                    sectAnchors(sectAnchors).
                    docTitle(title);

            globalConfig.getLayoutConfig().setHideFeaturesSection(hideFeaturesSection);

            globalConfig.getLayoutConfig().setHideSummarySection(hideSummary);

            globalConfig.getLayoutConfig().setHideScenarioKeyword(hideScenarioKeyword);

            globalConfig.getLayoutConfig().setHideStepTime(hideStepTime);

            globalConfig.getLayoutConfig().setHideTags(hideTags);


            String outputPath = targetBuildDirectory.getAbsolutePath();
            CukedoctorBuildAction action =  new CukedoctorBuildAction(build);
            String documentationLink = "";
            final ExecutorService pool = Executors.newFixedThreadPool(4);
            if ("all".equals(format.getFormat())) {
                documentationLink = "../" + CukedoctorBaseAction.BASE_URL+"/"+CukedoctorBaseAction.ALL_DOCUMENTATION;
                File allHtml = new File(outputPath + System.getProperty("file.separator") + CukedoctorBaseAction.ALL_DOCUMENTATION);
                if (!allHtml.exists()) {
                    boolean created = allHtml.createNewFile();
                    if (!created) {
                        listener.error("Could not create file at location: " + allHtml.getAbsolutePath());
                        result = Result.UNSTABLE;
                    }
                }
                InputStream is = null;
                OutputStream os = null;
                try {
                    is = getClass().getResourceAsStream("/" + CukedoctorBaseAction.ALL_DOCUMENTATION);
                    os = new FileOutputStream(allHtml);

                    int copyResult = IOUtils.copy(is, os);
                    if (copyResult == -1) {
                        listener.error("File is too big.");//will never reach here but findbugs forced it...
                        result = Result.UNSTABLE;
                    }
                } finally {
                    if (is != null) {
                        is.close();
                    }
                    if (os != null) {
                        os.close();
                    }
                }

                action.setDocumentationPage(CukedoctorBaseAction.ALL_DOCUMENTATION);
                pool.execute(runAll(features, documentAttributes, outputPath));
            } else {
                documentationLink = "../" + CukedoctorBaseAction.BASE_URL+"/documentation." + format.getFormat();
                action.setDocumentationPage("documentation." + format.getFormat());
                pool.execute(run(features, documentAttributes, outputPath));
            }

            build.addAction(action);
            pool.shutdown();
            try {
                if (format.equals(FormatType.HTML)) {
                    pool.awaitTermination(5, TimeUnit.MINUTES);
                } else {
                    pool.awaitTermination(15, TimeUnit.MINUTES);
                }
            } catch (final InterruptedException e) {
                Thread.interrupted();
                listener.error("Your documentation is taking too long to be generated. Halting the generation now to not throttle Jenkins.");
                result = Result.FAILURE;
            }
            
            if(result.equals(Result.SUCCESS)){
           	 listener.hyperlink(documentationLink, "Documentation generated successfully!");
                logger.println("");
            }

        } else {
            logger.println(String.format("No features Found in %s. %sLiving documentation will not be generated.", workspaceJsonTargetDir.getRemote(), "\n"));

        }

        build.setResult(result);
    }

    /**
     * mainly for findbugs be happy
     * @param build
     * @return
     */
    private FilePath getMasterWorkspaceDir(Run<?, ?> build) {
        if(build != null &&  build.getRootDir() != null){
            return new FilePath(build.getRootDir());
        } else{
        	return new FilePath(Paths.get("").toFile());
        }
    }

    /**
     * generates html and pdf documentation 'inlined' otherwise if we execute them in separated threads
     * only the last thread content is rendered (cause they work on the same adoc file)
     *
     * @return
     */
    private Runnable runAll(final List<Feature> features, final DocumentAttributes attrs, final String outputPath) {
        return new Runnable() {

            @Override
            public void run() {

                Asciidoctor asciidoctor = null;
                try {
                    /*
                     * this throws: ERROR: org.jruby.exceptions.RaiseException: (LoadError) no such file to load -- jruby/java
                     * asciidoctor = Asciidoctor.Factory.create();
                     */
                    asciidoctor = Asciidoctor.Factory.create(CukedoctorPublisher.class.getClassLoader());
                    attrs.backend("html5");
                    generateDocumentation(features, attrs, outputPath, asciidoctor);
                    attrs.backend("pdf");
                    generateDocumentation(features, attrs, outputPath, asciidoctor);

                } catch (Exception e) {
                    logger.println(String.format("Unexpected error on documentation generation, message %s, cause %s", e.getMessage(), e.getCause()));
                    e.printStackTrace();
                } finally {
                    if (asciidoctor != null) {
                        asciidoctor.shutdown();
                    }
                }
            }
        };

    }


    private Runnable run(final List<Feature> features, final DocumentAttributes attrs, final String outputPath) {
        return new Runnable() {

            @Override
            public void run() {
                Asciidoctor asciidoctor = null;
                try {
                    asciidoctor = Asciidoctor.Factory.create(CukedoctorPublisher.class.getClassLoader());
                    generateDocumentation(features, attrs, outputPath, asciidoctor);
                } catch (Exception e) {
                    logger.println(String.format("Unexpected error on documentation generation, message %s, cause %s", e.getMessage(), e.getCause()));
                    e.printStackTrace();
                } finally {
                    if (asciidoctor != null) {
                        asciidoctor.shutdown();
                    }
                }
            }
        };

    }


    protected synchronized void generateDocumentation(List<Feature> features, DocumentAttributes attrs, String outputPath, Asciidoctor asciidoctor) {
        asciidoctor.unregisterAllExtensions();
        if (!attrs.getBackend().equalsIgnoreCase("pdf")) {
            new CukedoctorExtensionRegistry().register(asciidoctor);
        }
        CukedoctorConverter converter = Cukedoctor.instance(features, attrs);
        String doc = converter.renderDocumentation();
        File adocFile = FileUtil.saveFile(outputPath + "/documentation.adoc", doc);
        asciidoctor.convertFile(adocFile, OptionsBuilder.options().backend(attrs.getBackend()).safe(SafeMode.UNSAFE).asMap());
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }


    @Extension @Symbol("livingDocs")
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {


        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }


        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Living documentation";
        }

        @Restricted(NoExternalUse.class) // Only for UI calls
        public ListBoxModel doFillTocItems() {
            ListBoxModel items = new ListBoxModel();
            for (TocType tocType : TocType.values()) {
                items.add(tocType.getToc(), tocType.name());
            }
            return items;
        }

        @Restricted(NoExternalUse.class) // Only for UI calls
        public ListBoxModel doFillFormatItems() {
            ListBoxModel items = new ListBoxModel();
            for (FormatType formatType : FormatType.values()) {
                items.add(formatType.getFormat(), formatType.name());
            }
            return items;
        }


    }


    public String getFeaturesDir() {
        return featuresDir;
    }

    public boolean isNumbered() {
        return numbered;
    }

    public boolean isSectAnchors() {
        return sectAnchors;
    }

    public TocType getToc() {
        return toc;
    }

    public FormatType getFormat() {
        return format;
    }

    public String getTitle() {
        return title;
    }

    public boolean isHideFeaturesSection() {
        return hideFeaturesSection;
    }

    public boolean isHideSummary() {
        return hideSummary;
    }

    public boolean isHideScenarioKeyword() {
        return hideScenarioKeyword;
    }

    public boolean isHideStepTime() {
        return hideStepTime;
    }

    public boolean isHideTags() {
        return hideTags;
    }


    @DataBoundSetter
    public void setFeaturesDir(String featuresDir) {
        this.featuresDir = featuresDir;
    }

    @DataBoundSetter
    public void setNumbered(boolean numbered) {
        this.numbered = numbered;
    }

    @DataBoundSetter
    public void setSectAnchors(boolean sectAnchors) {
        this.sectAnchors = sectAnchors;
    }

    @DataBoundSetter
    public void setToc(TocType toc) {
        this.toc = toc;
    }

    @DataBoundSetter
    public void setFormat(FormatType format) {
        this.format = format;
    }

    @DataBoundSetter
    public void setTitle(String title) {
        this.title = title;
    }

    @DataBoundSetter
    public void setHideFeaturesSection(boolean hideFeaturesSection) {
        this.hideFeaturesSection = hideFeaturesSection;
    }

    @DataBoundSetter
    public void setHideSummary(boolean hideSummary) {
        this.hideSummary = hideSummary;
    }

    @DataBoundSetter
    public void setHideScenarioKeyword(boolean hideScenarioKeyword) {
        this.hideScenarioKeyword = hideScenarioKeyword;
    }

    @DataBoundSetter
    public void setHideStepTime(boolean hideStepTime) {
        this.hideStepTime = hideStepTime;
    }

    @DataBoundSetter
    public void setHideTags(boolean hideTags) {
        this.hideTags = hideTags;
    }
}

