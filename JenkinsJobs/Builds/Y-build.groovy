pipeline {
	options {
		timeout(time: 390, unit: 'MINUTES')
		timestamps()
		buildDiscarder(logRotator(numToKeepStr:'5'))
	}
  agent {
    kubernetes {
      label 'aggrbuild-pod'
      defaultContainer 'container'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: "jnlp"
    image: "eclipsecbijenkins/jipp-migration-agent:4.3"
    imagePullPolicy: "IfNotPresent"
    resources:
      limits:
        memory: "8192Mi"
        cpu: "2000m"
      requests:
        memory: "6144Mi"
        cpu: "1000m"
    securityContext:
      privileged: false
    tty: true
    volumeMounts:
    - mountPath: "/home/jenkins/agent"
      name: "workspace-volume"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/toolchains.xml"
      name: "toolchains-xml"
      readOnly: true
      subPath: "toolchains.xml"
    - mountPath: "/opt/tools"
      name: "volume-0"
      readOnly: false
    - mountPath: "/home/jenkins"
      name: "volume-2"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/repository"
      name: "volume-3"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/settings-security.xml"
      name: "settings-security-xml"
      readOnly: true
      subPath: "settings-security.xml"
    - mountPath: "/home/jenkins/.m2/settings.xml"
      name: "settings-xml"
      readOnly: true
      subPath: "settings.xml"
    - mountPath: "/home/jenkins/.ssh"
      name: "volume-1"
      readOnly: false
    workingDir: "/home/jenkins/agent"
  nodeSelector: {}
  restartPolicy: "Never"
  volumes:
  - name: "settings-security-xml"
    secret:
      items:
      - key: "settings-security.xml"
        path: "settings-security.xml"
      secretName: "m2-secret-dir"
  - name: "volume-0"
    persistentVolumeClaim:
      claimName: "tools-claim-jiro-releng"
      readOnly: true
  - configMap:
      items:
      - key: "toolchains.xml"
        path: "toolchains.xml"
      name: "m2-dir"
    name: "toolchains-xml"
  - emptyDir:
      medium: ""
    name: "volume-2"
  - configMap:
      name: "known-hosts"
    name: "volume-1"
  - name: "settings-xml"
    secret:
      items:
      - key: "settings.xml"
        path: "settings.xml"
      secretName: "m2-secret-dir"
  - emptyDir:
      medium: ""
    name: "workspace-volume"
  - emptyDir:
      medium: ""
    name: "volume-3"
"""
    }
  }
  environment {
      MAVEN_OPTS = "-Xmx6G"
      CJE_ROOT = "${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production"
      PATH = "$PATH:/opt/tools/apache-maven/latest/bin"
      logDir = "$CJE_ROOT/buildlogs"
    }
  
  stages {
      stage('Clean Workspace'){
          steps {
              container('jnlp') {
                sh '''
                    cd $WORKSPACE
                    rm -rf *
                '''
                }
            }
	    }
	  stage('Setup intial configuration'){
          steps {
              container('jnlp') {
                  sshagent(['git.eclipse.org-bot-ssh']) {
                      dir ('eclipse.platform.releng.aggregator') {
                        sh '''
                            git clone -b master ssh://genie.releng@git.eclipse.org:29418/platform/eclipse.platform.releng.aggregator.git
                        '''
                      }
                    }
                    sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production
                        chmod +x mbscripts/*
                        mkdir -p $logDir
                    '''
                }
            }
		}
	  stage('Genrerate environment variables'){
          steps {
              container('jnlp') {
                sh '''
                    cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                    cp ../Y-build/buildproperties.txt ../buildproperties.txt
                    ./mb010_createEnvfiles.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb010_createEnvfiles.sh.log
                    if [[ ${PIPESTATUS[0]} -ne 0 ]]
                    then
                        echo "Failed in Genrerate environment variables stage"
                        exit 1
                    fi
                '''
                }
            }
		}
	  stage('Export environment variables stage 1'){
          steps {
              container('jnlp') {
                script {
                    env.BUILD_IID = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $BUILD_TYPE$TIMESTAMP)', returnStdout: true)
                    env.BUILD_VERSION = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                    env.STREAM = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $STREAM)', returnStdout: true)
                    env.EBUILDER_HASH = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $EBUILDER_HASH)', returnStdout: true)
                    env.RELEASE_VER = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                  }
                }
            }
        }
	  stage('Swt build input') {
	      steps {
	          build '1-SWT-Increment_if_needed_chromium'
	      }
	    }
	  stage('Create Base builder'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      withAnt(installation: 'apache-ant-latest') {
                        sh '''
                            cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                            ./mb020_createBaseBuilder.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb020_createBaseBuilder.sh.log
                            if [[ ${PIPESTATUS[0]} -ne 0 ]]
                            then
                                echo "Failed in Create Base builder stage"
                                exit 1
                            fi
                        '''
                      }
                  }
                }
            }
		}
	  stage('Download reference repo for repo reports'){
          steps {
              container('jnlp') {
                  sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                    sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb030_downloadBuildToCompare.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb030_downloadBuildToCompare.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Download reference repo for repo reports stage"
                            exit 1
                        fi
                    '''
                  }
                }
            }
		}
	  stage('Clone Repositories'){
          steps {
              container('jnlp') {
                  sshagent(['git.eclipse.org-bot-ssh']) {
                    sh '''
                        git config --global user.email "releng-bot@eclipse.org"
                        git config --global user.name "Eclipse Releng Bot"
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb100_cloneRepos.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb100_cloneRepos.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Clone Repositories stage"
                            exit 1
                        fi
                    '''
                  }
                }
            }
		}
	  stage('Tag Build Inputs'){
          steps {
              container('jnlp') {
                  sshagent (['git.eclipse.org-bot-ssh', 'projects-storage.eclipse.org-bot-ssh']) {
                    sh '''
                        git config --global user.email "releng-bot@eclipse.org"
                        git config --global user.name "Eclipse Releng Bot"
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        bash -x ./mb110_tagBuildInputs.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb110_tagBuildInputs.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Tag Build Inputs stage"
                            exit 1
                        fi
                    '''
                  }
                }
            }
		}
	  stage('Copy test configs for Y-build'){
          steps {
              container('jnlp') {
                    sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/Y-build
                        cp testConfigs.php ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/publishingFiles/staticDropFiles/.
                        cp testConfigs.php ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/publishingFiles/staticDropFiles/.
                        cp publish.xml ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/buildScripts/.
                        cp publish.xml ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/buildScripts/.
                        cp publish2.xml ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/buildScripts/.
                        cp publish2.xml ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/eclipse.platform.releng.tychoeclipsebuilder/eclipse/buildScripts/.
                    '''
                }
            }
		}
	  stage('Update Pom files in the source'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                  	sshagent(['git.eclipse.org-bot-ssh']) {
	                    sh '''
	                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
	                        unset JAVA_TOOL_OPTIONS 
	                        unset _JAVA_OPTIONS
	                        ./mb210_updatePom.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb210_updatePom.sh.log
	                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
	                        then
	                            echo "Failed in Update Pom files in the source stage"
	                            exit 1
	                        fi
	                    '''
	              	}
                  }
                }
            }
		}
	  stage('Aggregator maven build'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                    sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        unset JAVA_TOOL_OPTIONS 
                        unset _JAVA_OPTIONS
                        ./mb220_buildSdkPatch.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb220_buildSdkPatch.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Aggregator maven build stage"
                            exit 1
                        fi
                    '''
                  }
                }
            }
		}
	  stage('Gather Eclipse Parts'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      withAnt(installation: 'apache-ant-latest') {
                          sh '''
                            cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                            bash -x ./mb300_gatherEclipseParts.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb300_gatherEclipseParts.sh.log
                            if [[ ${PIPESTATUS[0]} -ne 0 ]]
                            then
                                echo "Failed in Gather Eclipse Parts stage"
                                exit 1
                            fi
                          '''
                      }
                  }
                }
            }
		}
	  stage('Gather Equinox Parts'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      withAnt(installation: 'apache-ant-latest') {
                          sh '''
                            cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                            ./mb310_gatherEquinoxParts.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb310_gatherEquinoxParts.sh.log
                            if [[ ${PIPESTATUS[0]} -ne 0 ]]
                            then
                                echo "Failed in Gather Equinox Parts stage"
                                exit 1
                            fi
                          '''
                      }
                  }
                }
            }
		}
	  stage('Generate Repo reports'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        unset JAVA_TOOL_OPTIONS 
                        unset _JAVA_OPTIONS
                        ./mb500_createRepoReports.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb500_createRepoReports.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Generate Repo reports stage"
                            exit 1
                        fi
                      '''
                  }
                }
            }
		}
	  stage('Generate API tools reports'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        unset JAVA_TOOL_OPTIONS 
                        unset _JAVA_OPTIONS
                        ./mb510_createApiToolsReports.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb510_createApiToolsReports.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Generate API tools reports stage"
                            exit 1
                        fi
                      '''
                  }
                }
            }
		}
	  stage('Export environment variables stage 2'){
          steps {
              container('jnlp') {
                script {
                    env.BUILD_IID = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $BUILD_TYPE$TIMESTAMP)', returnStdout: true)
                    env.BUILD_VERSION = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                    env.STREAM = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $STREAM)', returnStdout: true)
                    env.COMPARATOR_ERRORS_SUBJECT = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $COMPARATOR_ERRORS_SUBJECT)', returnStdout: true)
                    env.COMPARATOR_ERRORS_BODY = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $COMPARATOR_ERRORS_BODY)', returnStdout: true)
                    env.POM_UPDATES_SUBJECT = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $POM_UPDATES_SUBJECT)', returnStdout: true)
                    env.POM_UPDATES_BODY = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $POM_UPDATES_BODY)', returnStdout: true)
                    env.EBUILDER_HASH = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $EBUILDER_HASH)', returnStdout: true)
                    env.RELEASE_VER = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                  }
                }
            }
        }
	  stage('Archive artifacts'){
          steps {
              container('jnlp') {
                sh '''
                    cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production
                    source $CJE_ROOT/buildproperties.shsource
                    cp -r $logDir/* $CJE_ROOT/$DROP_DIR/$BUILD_ID/buildlogs
                    rm -rf $logDir
                    rm -rf $CJE_ROOT/$DROP_DIR/$BUILD_ID/apitoolingreference
                    cp $CJE_ROOT/buildproperties.txt $CJE_ROOT/$DROP_DIR/$BUILD_ID
                    cp $CJE_ROOT/buildproperties.php $CJE_ROOT/$DROP_DIR/$BUILD_ID
                    cp $CJE_ROOT/buildproperties.properties $CJE_ROOT/$DROP_DIR/$BUILD_ID
                    cp $CJE_ROOT/buildproperties.shsource $CJE_ROOT/$DROP_DIR/$BUILD_ID
                    cp $CJE_ROOT/$DROP_DIR/$BUILD_ID/buildproperties.* $CJE_ROOT/$EQUINOX_DROP_DIR/$BUILD_ID
                '''
              }
              archiveArtifacts '**/siteDir/**'
            }
		}
	  stage('Promote Eclipse platform'){
          steps {
              container('jnlp') {
                  sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                      sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb600_promoteEclipse.sh $CJE_ROOT/buildproperties.shsource
                      '''
                  }
                }
                build job: 'eclipse.releng.updateIndex', wait: false
            }
		}
	  stage('Promote Equinox'){
          steps {
              container('jnlp') {
                  sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                      sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb610_promoteEquinox.sh $CJE_ROOT/buildproperties.shsource
                      '''
                  }
                }
            }
		}
	  stage('Promote Update Site'){
          steps {
              container('jnlp') {
                  sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                      sh '''
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb620_promoteUpdateSite.sh $CJE_ROOT/buildproperties.shsource
                      '''
                  }
                }
            }
		}
	  stage('Trigger tests'){
          steps {
              container('jnlp') {
                build job: 'ep418Y-unit-cen64-gtk3-java11', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'ep418Y-unit-cen64-gtk3-java15', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'ep418Y-unit-cen64-gtk3-java16', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'ep418Y-unit-mac64-java11', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'ep418Y-unit-win32-java11', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'Start-smoke-tests', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
                build job: 'Smoke-tests-java16', parameters: [string(name: 'buildId', value: "${env.BUILD_IID.trim()}")], wait: false
              }
            }
		}
	}
	post {
        failure {
            emailext body: "Please go to ${BUILD_URL}/console and check the build failure.<br><br>",
            subject: "${env.BUILD_VERSION} Y-Build: ${env.BUILD_IID.trim()} - BUILD FAILED", 
            to: "jarthana@in.ibm.com sravankumarl@in.ibm.com kalyan_prasad@in.ibm.com daniel_megert@ch.ibm.com lshanmug@in.ibm.com manoj.palat@in.ibm.com niraj.modi@in.ibm.com noopur_gupta@in.ibm.com sarika.sinha@in.ibm.com vikas.chandra@in.ibm.com",
            from:"genie.releng@eclipse.org"
        }
        success {
            emailext body: "Eclipse downloads:<br>    https://download.eclipse.org/eclipse/downloads/drops4/${env.BUILD_IID.trim()}<br><br> Build logs and/or test results (eventually):<br>    https://download.eclipse.org/eclipse/downloads/drops4/${env.BUILD_IID.trim()}/testResults.php<br><br>${env.POM_UPDATES_BODY.trim()}${env.COMPARATOR_ERRORS_BODY.trim()}Software site repository:<br>    https://download.eclipse.org/eclipse/updates/4.18-Y-builds<br><br>Specific (simple) site repository:<br>    https://download.eclipse.org/eclipse/updates/4.18-Y-builds/${env.BUILD_IID.trim()}<br><br>Equinox downloads:<br>     https://download.eclipse.org/equinox/drops/${env.BUILD_IID.trim()}<br><br>", 
            subject: "${env.BUILD_VERSION} Y-Build: ${env.BUILD_IID.trim()} ${env.POM_UPDATES_SUBJECT.trim()} ${env.COMPARATOR_ERRORS_SUBJECT.trim()}", 
            to: "jarthana@in.ibm.com sravankumarl@in.ibm.com kalyan_prasad@in.ibm.com daniel_megert@ch.ibm.com lshanmug@in.ibm.com manoj.palat@in.ibm.com niraj.modi@in.ibm.com noopur_gupta@in.ibm.com sarika.sinha@in.ibm.com vikas.chandra@in.ibm.com",
            from:"genie.releng@eclipse.org"
        }
	}
}
