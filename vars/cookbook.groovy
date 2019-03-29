def call(body) {
    // evaluate the body of the Jenkinsfile
    def config = [:]
    def scmadm_credntialsId = 'GITHUB_SCMADM_SSH_KEY'
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Start the pipeline
    pipeline {
        agent any

        stages {
            // Start the parallel stages
            stage('Linting Checks') {
                failFast true
                parallel {
                    // Cookstyle stage
                    stage('cookstyle') {
                        steps {
                            sh "cookstyle ."
                        }
                    }
                    // Foodcritic stage
                    stage('foodcritic') {
                        steps {
                            sh "foodcritic ."
                        }
                    }
                }
            }

            // Start parallet checks for certain generic phrases
            stage('Sanity Checks') {
                failFast true
                parallel {
                    // Maintainer email
                    stage('valid email') {
                        steps {
                            sh '''
                                maintainer_email=`grep maintainer_email metadata.rb | awk '{print $2 }' | cut -d\\' -f2`
                                if [ "$maintainer_email" == "you@example.com" ] || [ "$maintainer_email" == "example@dyourcompany.com" ] && [ "$maintainer_email" != "*@yourcompany.com" ] ; then
                                  echo "This is not a valid email!"
                                  exit 1
                                fi
                            '''
                        }
                    }

                    // github org
                    stage('github org') {
                        steps {
                            sh '''
                                issues_url=`grep issues_url metadata.rb | awk '{ print $2 }' | cut -d\\' -f2 | cut -d\\/ -f4`
                                source_url=`grep source_url metadata.rb | awk '{ print $2 }' | cut -d\\' -f2 | cut -d\\/ -f4`
                                if [ "$issues_url" == "your-git-org" ] || [ "$source_url" == "your-git-org" ] ; then
                                  echo "This is not a valid git org"
                                  exit 1
                                fi
                            '''
                        }
                    }

                    stage('kitchen.yml') {
                        steps {
                            sh '''
                                if [ -f .kitchen.yml ] ; then
                                  echo ".kitchen.yml present!"
                                  exit 1
                                fi
                            '''
                        }
                    }

                    stage('berksfile') {
                        steps {
                            sh '''
                                grep https://supermarket.chef.io Berksfile
                                grep metadata Berksfile
                            '''
                        }
                    }

                    // Metadata check - eventually
                    stage('metadata version') {
                        when { branch 'chefqa' }
                        steps {
                            script {
                                sshagent(['GITHUB_SCMADM_SSH_KEY']) {
                                    sh '''
                                        git clone git@github.com:your_org/jenkins-pipeline.git
                                        git clone -b $GIT_BRANCH $GIT_URL
                                    '''
                                }
                                withCredentials([string(credentialsId: 'CHEF_DECRYPTION_KEY', variable: 'decrypt_key')]) {
                                    sh '''
                                        openssl aes-256-cbc -d -a -in jenkins-pipeline/knife_qa.rb.enc -out knife_qa.rb -k $decrypt_key
                                        openssl aes-256-cbc -d -a -in jenkins-pipeline/vrachefqa.pem.enc -out vrachefqa.pem -k $decrypt_key
                                    '''
                                }
                                sh '''
                                    COOKBOOK=`basename $GIT_URL | cut -d'.' -f1`
                                    CHEFVER=`knife cookbook list -c knife_qa.rb | grep $COOKBOOK | awk '{ print $2 }'`
                                    METAVER=`grep ^version metadata.rb | awk '{ print $2 }' | cut -d\\' -f2`
                                    if [ "$CHEFVER" == "$METAVER" ]; then
                                      echo "Version not increased"
                                      exit 1
                                    fi
                                '''
                            }
                        }
                    }
                }
            }

            // Red Flag Checks
            stage('Red Flags') {
                failFast true
                parallel {
                    // OS restricted config files
                    // Check for sudoers
                    stage('/etc/sudoers') {
                        steps {
                            sh '''
                            sudoer_ct=`grep -R /etc/sudoers * | grep -v ".git/" | wc -l`
                            if (( sudoer_ct > 0 )) ; then
                              echo "sudoers file modified"
                              exit 1
                            fi
                            '''
                        }
                    }

                    // Check for 
                    stage('/etc/profile') {
                        steps {
                            sh '''
                                prof_ct=`grep -R /etc/profile * | grep -v profile.d | grep -v ".git/" | wc -l`
                                if (( prof_ct > 0 )) ; then
                                  echo "/etc/profile modified"
                                  exit 1
                                fi
                            '''
                        }
                    }
                    
                    stage('sshd_config') {
                        steps {
                            sh '''
                                sshd_ct=`grep -R sshd_config * | grep -v ".git/" | wc -l`
                                if (( sshd_ct > 0 )) ; then
                                  echo "/etc/ssh/sshd_config modified"
                                  exit 1
                                fi
                            '''
                        }
                    }
                    
                    stage('limits.conf') {
                        steps {
                            sh '''
                                limits_ct=`grep -R limits.conf * | grep -v limits.d | grep -v ".git/" | wc -l`
                                if (( limits_ct > 0 )) ; then
                                  echo "/etc/security/limits.conf modified"
                                  exit 1
                                fi
                            '''
                        }
                    }

                    // check for ntp.conf mods
                    stage('ntp.conf') {
                        steps {
                            sh '''
                                ntp_ct=`grep -R /etc/ntp.conf * | grep -v ".git/" | wc -l`
                                if (( ntp_ct > 0 )) ; then
                                  echo "/etc/ntp.conf modified"
                                  exit 1
                                fi
                            '''

                        }
                    }

                    // check for chrony.conf mods
                    stage('chrony.conf') {
                        steps {
                            sh '''
                                chrony_ct=`grep -R /etc/chrony.conf * | grep -v ".git/" | wc -l`
                                if (( chrony_ct > 0 )) ; then
                                  echo "/etc/chrony.conf modified"
                                  exit 1
                                fi
                            '''

                        }
                    }
                }
            }

            // publish to dev chef server
            stage('Publish to ChefQA Server') {
                when { branch 'chefqa' }
                steps {
                    sh '''
                        knife ssl fetch https://chef-qa.yourcompany.com
                        knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_qa.rb
                        rm -f grommel.pem knife_qa.rb
                    '''
                }
            }

            // Publish to supermarket stage
            stage('Publish to Supermarket') {
                when { branch 'master' }
                steps {
                    script {
                        sshagent(['GITHUB_SCMADM_SSH_KEY']) {
                            sh '''
                                git clone git@github.com:your_org/jenkins-pipeline.git
                                git clone -b $GIT_BRANCH $GIT_URL
                            '''
                        }
                        withCredentials([string(credentialsId: 'CHEF_DECRYPTION_KEY', variable: 'decrypt_key')]) {
                            sh '''
                                openssl aes-256-cbc -d -a -in jenkins-pipeline/knife.rb.enc -out knife.rb -k $decrypt_key
                                openssl aes-256-cbc -d -a -in jenkins-pipeline/vrachefprod.pem.enc -out vrachefprod.pem -k $decrypt_key
                            '''
                            }
                            sh '''
                                knife ssl fetch https://upermarket.chef.io
                                knife cookbook site share `basename $GIT_URL | cut -d'.' -f1` -m https://supermarket.chef.io -c knife.rb
                            '''
                    }
                }
            }

            // publish to prod chef server
            stage('Publish to Production Server') {
                when { branch 'master' }
                steps {
                    sh '''
                        knife ssl fetch https://chef.yourcompany.com
                        knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife.rb
                        rm -f vrachefprod.pem knife.rb
                    '''
                }
            }
        }

        // start post steps
        post {

            always {
                echo "----- Clean Up After Yoself! -----"
                cleanWs()
            }

            failure {
                echo "----- FAILURE! -----"
                mail (to: "${config.emailIDs}",
                subject: "Pipeline failure: '${env.JOB_NAME}' (#${env.BUILD_ID})",
                body: "View the project build log <a href=\"${env.BUILD_URL}console\">here</a>",
                mimeType: 'text/html')
            }

            success {
                echo "----- SUCCESS! -----"
                mail (to: "${config.emailIDs}",
                subject: "Pipeline success: '${env.JOB_NAME}' (#${env.BUILD_ID})",
                body: "View the project build log <a href=\"${env.BUILD_URL}console\">here</a>",
                mimeType: 'text/html')
            }
        }
    }
}