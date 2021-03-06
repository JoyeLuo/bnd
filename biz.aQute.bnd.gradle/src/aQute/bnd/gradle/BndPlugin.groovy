/**
 * BndPlugin for Gradle.
 *
 * <p>
 * The plugin name is {@code biz.aQute.bnd}.
 *
 * <p>
 * If the bndWorkspace property is set, it will be used for the bnd Workspace.
 *
 * <p>
 * If the bnd_defaultTask property is set, it will be used for the the default
 * task.
 *
 * <p>
 * If the bnd_preCompileRefresh property is set to 'true', the project
 * properties will be refreshed just before compiling the project.
 */

package aQute.bnd.gradle

import aQute.bnd.build.Workspace
import aQute.bnd.osgi.Constants
import biz.aQute.resolve.Bndrun
import biz.aQute.resolve.WorkspaceResourcesRepository

import java.util.List

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger

import org.osgi.service.resolver.ResolutionException

public class BndPlugin implements Plugin<Project> {
  public static final String PLUGINID = 'biz.aQute.bnd'
  private Project project
  private aQute.bnd.build.Project bndProject
  private boolean preCompileRefresh

  /**
   * Apply the {@code biz.aQute.bnd} plugin to the specified project.
   */
  @Override
  public void apply(Project p) {
    p.configure(p) { project ->
      this.project = project
      if (plugins.hasPlugin(BndBuilderPlugin.PLUGINID)) {
          throw new GradleException("Project already has '${BndBuilderPlugin.PLUGINID}' plugin applied.")
      }
      if (!rootProject.hasProperty('bndWorkspace')) {
        rootProject.ext.bndWorkspace = new Workspace(rootDir).setOffline(rootProject.gradle.startParameter.offline)
      }
      this.bndProject = bndWorkspace.getProject(name)
      if (bndProject == null) {
        throw new GradleException("Unable to load bnd project ${name} from workspace ${rootDir}")
      }
      bndProject.prepare()
      if (!bndProject.isValid()) {
        checkErrors(logger)
        throw new GradleException("Project ${bndProject.getName()} is not a valid bnd project")
      }
      this.preCompileRefresh = project.hasProperty('bnd_preCompileRefresh') ? parseBoolean(bnd_preCompileRefresh) : false
      extensions.create('bnd', BndProperties, bndProject)
      convention.plugins.bnd = new BndPluginConvention(this)

      buildDir = relativePath(bndProject.getTargetDir())
      plugins.apply 'java'
      libsDirName = '.'
      testResultsDirName = bnd('test-reports', 'test-reports')

      if (project.hasProperty('bnd_defaultTask')) {
        defaultTasks = bnd_defaultTask.trim().split(/\s*,\s*/)
      }

      /* Set up configurations */
      configurations {
        dependson.description = 'bnd project dependencies.'
        runtime.artifacts.clear()
        archives.artifacts.clear()
      }
      /* Set up deliverables */
      bndProject.getDeliverables()*.getFile().each { deliverable ->
        artifacts {
          runtime(deliverable) {
             builtBy jar
          }
          archives(deliverable) {
             builtBy jar
          }
        }
      }
      /* Set up dependencies */
      dependencies {
        compile compilePath()
        runtime runtimePath()
        testCompile testCompilePath()
        testRuntime testRuntimePath()
      }
      /* Set up source sets */
      sourceSets {
        /* bnd uses the same directory for java and resources. */
        main {
          java.srcDirs = resources.srcDirs = files(bndProject.getSourcePath())
          output.classesDir = output.resourcesDir = bndProject.getSrcOutput()
        }
        test {
          java.srcDirs = resources.srcDirs = files(bndProject.getTestSrc())
          output.classesDir = output.resourcesDir = bndProject.getTestOutput()
        }
      }
      bnd.ext.allSrcDirs = files(bndProject.getAllsourcepath())
      /* Set up compile tasks */
      sourceCompatibility = bnd('javac.source', sourceCompatibility)
      def javacTarget = bnd('javac.target', targetCompatibility)
      def bootclasspath = files(bndProject.getBootclasspath()*.getFile())
      if (javacTarget == 'jsr14') {
        javacTarget = '1.5'
        bootclasspath = files(bndProject.getBundle('ee.j2se', '1.5', null, ['strategy':'lowest']).getFile())
      }
      targetCompatibility = javacTarget
      def javac = bnd('javac')
      def javacProfile = bnd('javac.profile', '')
      def javacDebug = parseBoolean(bnd('javac.debug'))
      def javacDeprecation = parseBoolean(bnd('javac.deprecation', 'true'))
      def javacEncoding = bnd('javac.encoding', 'UTF-8')
      def compileOptions = {
        if (javacDebug) {
          debugOptions.debugLevel = 'source,lines,vars'
        }
        verbose = logger.isDebugEnabled()
        listFiles = logger.isInfoEnabled()
        deprecation = javacDeprecation
        encoding = javacEncoding
        if (javac != 'javac') {
          fork = true
          forkOptions.executable = javac
        }
        if (!bootclasspath.empty) {
          fork = true
          bootClasspath = bootclasspath.asPath
        }
        if (!javacProfile.empty) {
          compilerArgs += ['-profile', javacProfile]
        }
      }

      compileJava {
        configure options, compileOptions
        if (logger.isInfoEnabled()) {
          doFirst {
            logger.info 'Compile {} to {}', sourceSets.main.java.sourceDirectories.asPath, destinationDir
            if (javacProfile.empty) {
              logger.info '-source {} -target {}', sourceCompatibility, targetCompatibility
            } else {
              logger.info '-source {} -target {} -profile {}', sourceCompatibility, targetCompatibility, javacProfile
            }
            logger.info '-classpath {}', classpath.asPath
            if (options.bootClasspath != null) {
              logger.info '-bootclasspath {}', options.bootClasspath
            }
          }
        }
        doFirst {
            checkErrors(logger)
        }
        if (preCompileRefresh) {
          doFirst {
            logger.info 'Refreshing the bnd Project before compilation.'
            bndProject.refresh()
            bndProject.propertiesChanged()
            bndProject.clear()
            bndProject.prepare()
            classpath = compilePath()
          }
        }
      }

      compileTestJava {
        configure options, compileOptions
        doFirst {
            checkErrors(logger)
        }
        if (preCompileRefresh) {
          doFirst {
            logger.info 'Refreshing the bnd Project before compilation.'
            bndProject.refresh()
            bndProject.propertiesChanged()
            bndProject.clear()
            bndProject.prepare()
            classpath = files(testCompilePath(), runtimePath(), compilePath())
          }
        }
      }

      processResources {
        outputs.files {
          def sourceDirectories = sourceSets.main.resources.sourceDirectories
          def outputDir = sourceSets.main.output.resourcesDir
          inputs.files.collect {
            def input = it.absolutePath
            sourceDirectories.each {
              input -= it
            }
            new File(outputDir, input)
          }
        }
      }

      processTestResources {
        outputs.files {
          def sourceDirectories = sourceSets.test.resources.sourceDirectories
          def outputDir = sourceSets.test.output.resourcesDir
          inputs.files.collect {
            def input = it.absolutePath
            sourceDirectories.each {
              input -= it
            }
            new File(outputDir, input)
          }
        }
      }

      jar {
        description 'Assemble the project bundles.'
        deleteAllActions() /* Replace the standard task actions */
        enabled !bndProject.isNoBundles()
        ext.projectDirInputsExcludes = [] /* Additional excludes for projectDir inputs */
        /* all other files in the project like bnd and resources */
        inputs.files {
          fileTree(projectDir) { tree ->
            sourceSets.each { sourceSet -> /* exclude sourceSet dirs */
              tree.exclude sourceSet.allSource.sourceDirectories.collect {
                project.relativePath(it)
              }
              tree.exclude sourceSet.output.collect {
                project.relativePath(it)
              }
            }
            tree.exclude project.relativePath(buildDir) /* exclude buildDir */
            tree.exclude projectDirInputsExcludes /* user specified excludes */
          }
        }
        /* bnd can include any class on the buildpath */
        inputs.files {
          compileJava.classpath
        }
        /* Workspace and project configuration changes should trigger jar task */
        inputs.files bndProject.getWorkspace().getPropertiesFile(),
          bndProject.getWorkspace().getIncluded() ?: [],
          bndProject.getPropertiesFile(),
          bndProject.getIncluded() ?: []
        outputs.files {
          configurations.archives.artifacts.files
        }
        outputs.file new File(buildDir, Constants.BUILDFILES)
        doLast {
          def built
          try {
            built = bndProject.build()
          } catch (Exception e) {
            throw new GradleException("Project ${bndProject.getName()} failed to build", e)
          }
          checkErrors(logger)
          if (built != null) {
            logger.info 'Generated bundles: {}', built
          }
        }
      }

      task('release') {
        description 'Release the project to the release repository.'
        dependsOn assemble
        group 'release'
        enabled !bndProject.isNoBundles() && !bnd(Constants.RELEASEREPO, 'unset').empty
        inputs.files jar
        doLast {
          try {
            bndProject.release()
          } catch (Exception e) {
            throw new GradleException("Project ${bndProject.getName()} failed to release", e)
          }
          checkErrors(logger)
        }
      }

      task('releaseNeeded') {
        description 'Release the project and all projects it depends on.'
        dependsOn release
        group 'release'
      }

      test {
        enabled !parseBoolean(bnd(Constants.NOJUNIT, 'false')) && !parseBoolean(bnd('no.junit', 'false'))
        doFirst {
          checkErrors(logger, ignoreFailures)
        }
      }

      task('testOSGi') {
        description 'Runs the OSGi JUnit tests by launching a framework and running the tests in the launched framework.'
        dependsOn assemble
        group 'verification'
        enabled !parseBoolean(bnd(Constants.NOJUNITOSGI, 'false')) && !bndUnprocessed(Constants.TESTCASES, '').empty
        ext.ignoreFailures = false
        inputs.files jar
        outputs.dir {
          testResultsDir
        }
        doLast {
          try {
            bndProject.test()
          } catch (Exception e) {
            throw new GradleException("Project ${bndProject.getName()} failed to test", e)
          }
          checkErrors(logger, ignoreFailures)
        }
      }

      check {
        dependsOn testOSGi
      }

      task('checkNeeded') {
        description 'Runs all checks on the project and all projects it depends on.'
        dependsOn check
        group 'verification'
      }

      clean {
        description 'Cleans the build and compiler output directories of the project.'
        deleteAllActions() /* Replace the standard task actions */
        doLast {
          bndProject.clean()
        }
      }

      task('cleanNeeded') {
        description 'Cleans the project and all projects it depends on.'
        dependsOn clean
        group 'build'
      }

      tasks.addRule('Pattern: export.<name>: Export the <name>.bndrun file to an executable jar.') { taskName ->
        if (taskName.startsWith('export.')) {
          def bndrun = taskName - 'export.'
          def runFile = file("${bndrun}.bndrun")
          if (runFile.isFile()) {
            task(taskName) {
              description "Export the ${bndrun}.bndrun file to an executable jar."
              dependsOn assemble
              group 'export'
              ext.destinationDir = new File(distsDir, 'executable')
              outputs.file {
                new File(destinationDir, "${bndrun}.jar")
              }
              doFirst {
                project.mkdir(destinationDir)
              }
              doLast {
                def executableJar = new File(destinationDir, "${bndrun}.jar")
                logger.info 'Exporting {} to {}', runFile.absolutePath, executableJar.absolutePath
                try {
                  bndProject.export(relativePath(runFile), false, executableJar)
                } catch (Exception e) {
                  throw new GradleException("Export of ${runFile.absolutePath} to an executable jar failed", e)
                }
                checkErrors(logger)
              }
            }
          }
        }
      }

      task('export') {
        description 'Export all the bndrun files to runnable jars.'
        group 'export'
        fileTree(projectDir) {
            include '*.bndrun'
        }.each {
          dependsOn tasks.getByPath("export.${it.name - '.bndrun'}")
        }
      }

      tasks.addRule('Pattern: runbundles.<name>: Create a distribution of the runbundles in <name>.bndrun file.') { taskName ->
        if (taskName.startsWith('runbundles.')) {
          def bndrun = taskName - 'runbundles.'
          def runFile = file("${bndrun}.bndrun")
          if (runFile.isFile()) {
            task(taskName) {
              description "Create a distribution of the runbundles in the ${bndrun}.bndrun file."
              dependsOn assemble
              group 'export'
              ext.destinationDir = new File(distsDir, "runbundles/${bndrun}")
              outputs.dir {
                destinationDir
              }
              doFirst {
                project.delete(destinationDir)
                project.mkdir(destinationDir)
              }
              doLast {
                logger.info 'Creating a distribution of the runbundles from {} in directory {}', runFile.absolutePath, destinationDir.absolutePath
                try {
                    bndProject.exportRunbundles(relativePath(runFile), destinationDir)
                } catch (Exception e) {
                  throw new GradleException("Creating a distribution of the runbundles in ${runFile.absolutePath} failed", e)
                }
                checkErrors(logger)
              }
            }
          }
        }
      }

      task('runbundles') {
        description 'Create a distribution of the runbundles in each of the bndrun files.'
        group 'export'
        fileTree(projectDir) {
            include '*.bndrun'
        }.each {
          dependsOn tasks.getByPath("runbundles.${it.name - '.bndrun'}")
        }
      }

      tasks.addRule('Pattern: resolve.<name>: Resolve the required runbundles in the <name>.bndrun file.') { taskName ->
        if (taskName.startsWith('resolve.')) {
          def bndrun = taskName - 'resolve.'
          def runFile = file("${bndrun}.bndrun")
          if (runFile.isFile()) {
            task(taskName) {
              description "Resolve the runbundles required for ${bndrun}.bndrun file."
              dependsOn assemble
              group 'export'
              ext.failOnChanges = false
              outputs.file runFile
              doLast {
                logger.info 'Resolving runbundles required for {}', runFile.absolutePath
                Bndrun.createBndrun(bndProject.getWorkspace(), runFile).withCloseable { run ->
                  if (run.isStandalone()) {
                    run.getWorkspace().setOffline(bndProject.getWorkspace().isOffline())
                  }
                  try {
                    def result = run.resolve(failOnChanges, true)
                    logger.info '{}: {}', Constants.RUNBUNDLES, result
                  } catch (ResolutionException e) {
                    logger.error 'Unresolved requirements: {}', e.getUnresolvedRequirements()
                    throw new GradleException("${bndrun}.bndrun resolution failure", e)
                  }
                  checkProjectErrors(run, logger)
                }
              }
            }
          }
        }
      }

      task('resolve') {
        description 'Resolve the runbundles required for each of the bndrun files.'
        group 'export'
        fileTree(projectDir) {
            include '*.bndrun'
        }.each {
          dependsOn tasks.getByPath("resolve.${it.name - '.bndrun'}")
        }
      }

      tasks.addRule('Pattern: run.<name>: Run the bndrun file <name>.bndrun.') { taskName ->
        if (taskName.startsWith('run.')) {
          def bndrun = taskName - 'run.'
          def runFile = file("${bndrun}.bndrun")
          if (runFile.isFile()) {
            task(taskName) {
              description "Run the bndrun file ${bndrun}.bndrun."
              dependsOn assemble
              group 'export'
              doLast {
                Bndrun.createBndrun(bndProject.getWorkspace(), runFile).withCloseable { run ->
                  logger.lifecycle "Running {} with vm args: {}", bndrun, run.mergeProperties(Constants.RUNVM)
                  if (run.isStandalone()) {
                    run.getWorkspace().setOffline(bndProject.getWorkspace().isOffline())
                  }
                  run.run()
                  checkProjectErrors(run, logger)
                }
              }
            }
          }
        }
      }

      task('echo') {
        description 'Displays the bnd project information.'
        group 'help'
        doLast {
          println """
------------------------------------------------------------
Project ${project.name}
------------------------------------------------------------

project.workspace:      ${rootDir}
project.name:           ${project.name}
project.dir:            ${projectDir}
target:                 ${buildDir}
project.dependson:      ${bndProject.getDependson()*.getName()}
project.sourcepath:     ${sourceSets.main.java.sourceDirectories.asPath}
project.output:         ${compileJava.destinationDir}
project.buildpath:      ${compileJava.classpath.asPath}
project.allsourcepath:  ${bnd.allSrcDirs.asPath}
project.testsrc:        ${sourceSets.test.java.sourceDirectories.asPath}
project.testoutput:     ${compileTestJava.destinationDir}
project.testpath:       ${compileTestJava.classpath.asPath}
project.bootclasspath:  ${compileJava.options.bootClasspath}
project.deliverables:   ${configurations.archives.artifacts.files*.path}
javac:                  ${compileJava.options.forkOptions.executable}
javac.source:           ${sourceCompatibility}
javac.target:           ${targetCompatibility}
javac.profile:          ${javacProfile}
"""
          checkErrors(logger, true)
        }
      }

      task('bndproperties') {
        description 'Displays the bnd properties.'
        group 'help'
        doLast {
          println """
------------------------------------------------------------
Project ${project.name}
------------------------------------------------------------
"""
          bndProject.getPropertyKeys(true).sort().each {
            println "${it}: ${bnd(it, '')}"
          }
          println()
          checkErrors(logger, true)
        }
      }

      /* Set up dependencies */
      bndProject.getDependson()*.getName().each { dependency ->
        dependencies { handler ->
          compile handler.project('path': ":${dependency}", 'configuration': 'dependson')
        }
        compileJava.dependsOn(":${dependency}:assemble")
        checkNeeded.dependsOn(":${dependency}:checkNeeded")
        releaseNeeded.dependsOn(":${dependency}:releaseNeeded")
        cleanNeeded.dependsOn(":${dependency}:cleanNeeded")
      }
    }
  }

  private FileCollection compilePath() {
    return project.files(bndProject.getBuildpath()*.getFile())
  }

  private FileCollection testCompilePath() {
    return project.files(bndProject.getTestpath()*.getFile())
  }

  private FileCollection runtimePath() {
    return project.files(bndProject.getSrcOutput())
  }

  private FileCollection testRuntimePath() {
    return project.files(bndProject.getTestOutput())
  }

  private void checkErrors(Logger logger, boolean ignoreFailures = false) {
    checkProjectErrors(bndProject, logger, ignoreFailures)
  }

  private void checkProjectErrors(aQute.bnd.build.Project project, Logger logger, boolean ignoreFailures = false) {
    project.getInfo(project.getWorkspace(), "${project.getWorkspace().getBase().name} :")
    boolean failed = !ignoreFailures && !project.isOk()
    int errorCount = project.getErrors().size()
    project.getWarnings().each { msg ->
      def location = project.getLocation(msg)
      if (location && location.file) {
        logger.warn '{}:{}: warning\n{}', location.file, location.line, msg
      } else {
        logger.warn 'warning: {}', msg
      }
    }
    project.getWarnings().clear()
    project.getErrors().each { msg ->
      def location = project.getLocation(msg)
      if (location && location.file) {
        logger.error '{}:{}: error\n{}', location.file, location.line, msg
      } else {
        logger.error 'error  : {}', msg
      }
    }
    project.getErrors().clear()
    if (failed) {
      def str = ' even though no errors were reported'
      if (errorCount == 1) {
        str = ', one error was reported'
      } else if (errorCount > 1) {
        str = ", ${errorCount} errors were reported"
      }
      throw new GradleException("${project.getName()} has errors${str}")
    }
  }

  private boolean parseBoolean(String value) {
    return 'on'.equalsIgnoreCase(value) || 'true'.equalsIgnoreCase(value)
  }
}

