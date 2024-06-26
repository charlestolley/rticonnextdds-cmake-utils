/*
 * (c) Copyright, Real-Time Innovations, 2021.  All rights reserved.
 * RTI grants Licensee a license to use, modify, compile, and create derivative
 * works of the software solely for use with RTI Connext DDS. Licensee may
 * redistribute copies of the software provided that all such copies are subject
 * to this license. The software is provided "as is", with no warranty of any
 * type, including any warranty for fitness for any purpose. RTI is under no
 * obligation to maintain or support the software. RTI shall not be liable for
 * any incidental or consequential damages arising out of the use or inability
 * to use the software.
 */

/**
 * Hold information about the pipeline. E.g.: cmakeUtilsRepoDir, cmakeUtilsDockerDir,
 * staticAnalysisDir, connextDir.
 */
Map pipelineInfo = [:]

/**
 * Apply a patch to the examples repository depending on the branch. The patch consinsts in files
 * replacing and additions.
 *
 * @param cmakeUtilsRepoRoot Path to the root of the cmake-utils repository.
 * @param examplesRepoRoot Path to the root of the examples repository.
 * @param examplesRepoBranch The examples repository branch.
 */
void applyExamplesRepoPatch(
    String cmakeUtilsRepoRoot,
    String examplesRepoRoot,
    String examplesRepoBranch
) {
    Map patches = [
        'release/6.1.2': '6.1.2'
    ]
    String selectedPatch = patches[examplesRepoBranch] ?: 'submodule'

    def patch = load("${cmakeUtilsRepoRoot}/resources/ci/patches/${selectedPatch}Patch.groovy")
    patch.apply(cmakeUtilsRepoRoot, examplesRepoRoot)
}

pipeline {
    agent {
        label "${runInsideExecutor.labelFromArchitectureFamily(params.ARCHITECTURE_FAMILY)}"
    }

    options {
        skipDefaultCheckout()
        /*
            To avoid excessive resource usage in server, we limit the number
            of builds to keep in pull requests
        */
        buildDiscarder(
            logRotator(
                artifactDaysToKeepStr: '',
                artifactNumToKeepStr: '',
                daysToKeepStr: '',
                /*
                   For pull requests only keep the last 10 builds, for regular
                   branches keep up to 20 builds.
                */
                numToKeepStr: changeRequest() ? '10' : '20'
            )
        )
        // Set a timeout for the entire pipeline
        timeout(time: 2, unit: 'HOURS')
    }

    parameters {
        string(
            name: 'ARCHITECTURE_FAMILY',
            description: 'The architecture family to guess the node from (linux, windows, ...)',
            trim: true,
        )
        string(
            name: 'ARCHITECTURE_STRING',
            description: 'The architecture string (x64Linux4gcc7.3.0, x64Win64VS2015, ...)',
            trim: true,
        )
        string(
            name: 'EXAMPLES_REPOSITORY_BRANCH',
            description: (
                'The rticonnextdds-examples repository branch to build. E.g.: "master",'
                + ' "release/7.3.0", "PR-123"'
            ),
            trim: true,
        )
        string(
            name: 'CMAKE_UTILS_REPOSITORY_BRANCH',
            description: 'The rticonnextdds-cmake-utils repository branch to use',
            defaultValue: 'main',
            trim: true,
        )
    }

    stages {
        stage('Repository configuration') {
            steps {
                script {
                    pipelineInfo.cmakeUtilsRepoDir = "${env.WORKSPACE}/cmake-utils"
                    pipelineInfo.cmakeUtilsDockerDir = (
                        "${pipelineInfo.cmakeUtilsRepoDir}/resources/ci/docker/"
                    )
                    pipelineInfo.staticAnalysisDir = "${env.WORKSPACE}/static_analysis_report"
                }
                checkoutCommunityRepoBranch(
                    'rticonnextdds-examples',
                    params.EXAMPLES_REPOSITORY_BRANCH,
                    true,
                )
                dir(pipelineInfo.cmakeUtilsRepoDir) {
                    checkoutCommunityRepoBranch(
                        'rticonnextdds-cmake-utils',
                        params.CMAKE_UTILS_REPOSITORY_BRANCH,
                    )
                }
                applyExamplesRepoPatch(
                    pipelineInfo.cmakeUtilsRepoDir,
                    env.WORKSPACE,
                    params.EXAMPLES_REPOSITORY_BRANCH,
                )
            }
        }
        stage('Download Packages') {
            steps {
                runInsideExecutor(
                    params.ARCHITECTURE_STRING,
                    pipelineInfo.cmakeUtilsDockerDir,
                ) {
                    script {
                        pipelineInfo.connextDir = installConnext(
                            params.ARCHITECTURE_STRING,
                            env.WORKSPACE,
                        )
                    }
                }
            }
        }
        stage('Build all modes') {
            matrix {
                axes {
                    axis {
                        name 'buildMode'
                        values 'release', 'debug'
                    }
                    axis {
                        name 'linkMode'
                        values 'static', 'dynamic'
                    }
                }
                stages {
                    stage('Build single mode') {
                        steps {
                            runInsideExecutor(
                                params.ARCHITECTURE_STRING,
                                pipelineInfo.cmakeUtilsDockerDir,
                            ) {
                                echo("Building ${buildMode}/${linkMode}")
                                buildExamples(
                                    params.ARCHITECTURE_STRING,
                                    pipelineInfo.connextDir,
                                    buildMode,
                                    linkMode,
                                    env.WORKSPACE,
                                )
                            }
                        }
                    }
                }
            }
        }
        stage('Static Analysis') {
            steps {
                runInsideExecutor(
                    params.ARCHITECTURE_STRING,
                    pipelineInfo.cmakeUtilsDockerDir,
                ) {
                    runStaticAnalysis(
                        buildExamples.getBuildDirectory('release', 'dynamic'),
                        pipelineInfo.connextDir,
                        pipelineInfo.staticAnalysisDir,
                    )
                }
            }
            post {
                failure {
                    publishHTML(target: [
                        allowMissing: true,
                        alwaysLinkToLastBuild: false,
                        keepAll: true,
                        reportDir: pipelineInfo.staticAnalysisDir,
                        reportFiles: 'index.html',
                        reportName: 'LLVM Scan build static analysis',
                    ])
                }
            }
        }
    }
    post {
        cleanup {
            cleanWs()
        }
    }
}
