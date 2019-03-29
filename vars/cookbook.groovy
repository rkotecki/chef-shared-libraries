def call(body) {
  // evaluate the body of the Jenkinsfile
  def config = [:]
  def scmadm_credentialsId = 'GITHUB_SCMADM_SSH_KEY'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  pipeline {
    agent any

    options {
      ansiColor('xterm')
    }

    environment {
      SNOW_URL = "https://yourcompany.service-now.com"
    }

    stages {
      stage('Linting Checks') {
        failFast true
        parallel {
          stage('Cookstyle') {
            steps {
              echo "----- COOKSTYLE CHECKS -----"
              sh "cookstyle ."
            }
          }

          stage('Foodcritic') {
            steps {
              echo "----- FOODCRITIC CHECKS -----"
              sh "foodcritic ."
            }
          }
        }
      }

      stage('Sanity Checks') {
        failFast true
        parallel {
          stage('Valid Email') {
            steps {
              sh '''
                maintainer_email=`grep maintainer_email metadata.rb | awk '{ print $2 }' | cut -d \\' -f2`
                if [ "$maintainer_email" == "you@example.com" ] || [ "$maintainer_email"  == "example@yourcompany.com" ] && [ "$maintainer_email" != "*@yourcompany.com" ]; then
                  echo "This is NOT a valid email"
                  exit 1
                fi
              '''
            }
          }

          stage('GitHub Org') {
            steps {
              sh '''
                issues_url=`grep issues_url metadata.rb | awk '{ print $2 }' | cut -d \\' -f2 | cut -d \\/ -f4`
                source_url=`grep source_url metadata.rb | awk '{ print $2 }' | cut -d \\' -f2 | cut -d \\/ -f4`
                if [ "$issues_url" == "your-git_org" ] || [ "$source_url"  == "your-git-org" ]; then
                  echo "This is NOT a valid Git org!"
                  exit 2
                fi
              '''
            }
          }

          stage('kitchen.yml') {
            steps {
              sh '''
                if [ -f .kitchen.yml ]; then
                  echo ".kitchen.yml file is present!"
                  echo "This file can contain passwords"
                  exit 3
                fi
              '''
            }
          }

          stage('Berksfile') {
            steps {
              sh '''
                grep https://supermarket.chef.io Berksfile
                grep metadata Berksfile
              '''
            }
          }

          stage('Metadata Version') {
            when { branch 'chefqa' }
            steps {
              script {
                sshagent(['GITHUB_SCMADM_SSH_KEY']) {
                  sh '''
                    git clone git@github.com:your-org/jenkins-pipeline.git
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
                    echo "Version is node changed!"
                    exit 4
                  fi
                '''
              }
            }
          }
        }

        stage('Red Flags') {
          failFast true
          parallel {
            stage('/etc/sudoers') {
              steps {
                sh '''
                  sudoer_ct=`grep -R /etc/sudoers * | grep -v ".git/" | wc -l`
                  if (( sudoer_ct > 0 )); then
                    echo "sudoers file modified!"
                    exit 5
                  fi
                '''
              }
            }

            stage('/etc/profile') {
              steps {
                sh '''
                  profile_ct=`grep -R /etc/profile * | grep -v profile.d | grep -v ".git/" | wc -l`
                  if (( profile_ct > 0 )); then
                    echo "/etc/profile modified!"
                    exit 6
                  fi
                '''
              }
            }

            stage('sshd_config') {
              steps {
                sh '''
                  sshd_ct=`grep -R sshd_config * | grep -v ".git/" | wc -l`
                  if (( sshd_ct > 0 )); then
                    echo "/etc/ssh/sshd_config modified!"
                    exit 7
                  fi
                '''
              }
            }

            stage('limits.conf') {
              steps {
                sh '''
                  limits_ct=`grep -R limits.conf * | grep -v limits.d | grep -v ".git/" | wc -l`
                  if (( limits_ct > 0 )); then
                    echo "/etc/security/limits.conf modified!"
                    exit 8
                  fi
                '''
              }
            }

            tage('ntp.conf') {
              steps {
                sh '''
                  ntp_ct=`grep -R /etc/ntp.conf * | grep -v ".git/" | wc -l`
                  if (( ntp_ct > 0 )); then
                    echo "/etc/ntp.conf modified!"
                    exit 9
                  fi
                '''
              }
            }

            stage('chrony.conf') {
              steps {
                sh '''
                  chrony_ct=`grep -R /etc/chrony.conf * | grep -v ".git/" | wc -l`
                  if (( chrony_ct > 0 )); then
                    echo "/etc/chrony.conf modified!"
                    exit 10
                  fi
                '''
              }
            }
          }
        }

        stage('Publish to ChefQA Server') {
          when { branch 'chefqa' }
          steps {
            sh '''
              knife ssl fetch https://chef-qa.yourcompany.com
              knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_qa.rb
              rm -f vrachefqa.pem knife_qa.rb
            '''
          }
        }

        stage('Verify ServiceNow Change') {
          when { branch 'master' }
          steps {
            script {
              sshagent(['GITHUB_SCMADM_SSH_KEY']) {
                sh '''
                  git clone git@github.com:your-org/jenkins-pipeline.git
                '''
              }
              withCredentials([string(credentialsId: 'CHEF_DECRYPTION_KEY', variable: 'decrypt_key')]) {
                sh '''
                  openssl aes-256-cbc -d -a -in jenkins-pipeline/knife_dc1.rb.enc -out knife_dc1.rb -k $decrypt_key
                  openssl aes-256-cbc -d -a -in jenkins-pipeline/knife_dc2.rb.enc -out knife_dc2.rb -k $decrypt_key
                  openssl aes-256-cbc -d -a -in jenkins-pipeline/datacenter1.pem.enc -out datacenter1.pem -k $decrypt_key
                  openssl aes-256-cbc -d -a -in jenkins-pipeline/datacenter2.pem.enc -out datacenter2.pem -k $decrypt_key
                '''
              }
              sh '''
                set +x
                COOKBOOK=`cat metadata.rb | grep ^name | awk '{ print $2 }' | sed -e "s/'//g"`

                if grep -Fxq "$COOKBOOK" jenkins-pipeline/blacklist.txt; then
                  echo "----- This Cookbook Is Blacklisted -----"
                  echo "----- ServiceNow Validation Required -----"

                  echo "----- Making Call to ServiceNow -----"
                  curl "${SNOW_URL}/api/now/v2/table/change_request?sysparm_fields=number%2Cu_stage%2Cstart_date%2Cend_date&sysparm_limit=10&number=${config.CHG_NUM}" \
                  -x proxy.yourcompany.com:8080 \
                  --request GET \
                  --header "Accept:application/json" \
                  -o output.json \
                  --user 'username':'password'

                  STATUS=`cat output.json | jq -r '.result[].u_stage'`
                  if [ "$STATUS" != "scheduled" ]; then
                    echo "----- Your change is NOT fully approved -----"
                    exit 11
                  else
                    DATETIME_UTC=`date -u '+%Y%m%d%H%M%S'`
                    START_DATETIME=`cat output.json | jq -r '.result[].start_date' | sed 's/-//g' | sed 's/://g' | sed 's/ //g'`
                    END_DATETIME=`cat output.json | jq -r '.result[].end_date' | sed 's/-//g' | sed 's/://g' | sed 's/ //g'`

                    echo "----- Verifying Change Window Status -----"
                    if [[ "$DATETIME_UTC" -ge "$START_DATETIME" ]] && [[ "$DATETIME_UTC" -le "$END_DATETIME" ]]; then
                      echo "---- Your Change Window Is Open -----"
                    else
                      echo "----- Your Change Window Is NOT Open -----"
                      exit 12
                    fi
                  fi

                  echo "TRUE" > one_dc.txt
                  DATACENTER=`cat output.json | jq r '.result[].data_center'`
                  echo $DATACENTER > dc.txt

                else
                  echo "----- No ServiceNow Validation Needed -----"
                  echo "FALSE" > one_dc.txt
                fi
              '''
            }
          }
        }

        stage('Publish to Supermarket') {
          steps {
            script {
              sshagent(['GITHUB_SCMADM_SSH_KEY']) {
                sh '''
                  git clone -b $GIT_BRANCH $GIT_URL
                '''
              }
              sh '''
                knife ssl fetch https://supermarket.chef.io
                knife cookbook site share `basename $GIT_URL | cut -d'.' -f1` -m https://supermarket.chef.io -c knife_dc1.rb
              '''
            }
          }
        }

        stage('Publish to Production Server') {
          steps {
            sh '''
              knife ssl fetch https://chef.yourcompany.com

              STATE=`cat one_dc.txt`
              DATACENTER=`cat dc.txt`

              if [ "$STATE" == "FALSE" ]; then
                echo "----- Uploading To Datacenter 1 -----"
                knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_dc1.rb
                echo "----- Uploading To Datacenter 2 -----"
                knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_dc2.rb
                rm -f knife_dc1.rb knife_dc2.rb datacenter1.pem datacenter2.pem
              else
                if [ "$DATACENTER" == "DataCenter1" ]; then
                  echo "----- Uploading To Datacenter 1 -----"
                  knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_dc1.rb
                elif [ "$DATACENTER" == "DataCenter2" ]; then
                  echo "----- Uploading To Datacenter 2 -----"
                  knife cookbook upload `basename $GIT_URL | cut -d'.' -f1` -c knife_dc2.rb
              fi
            '''
          }
        }
      }
    }

    post {
      always {
        echo "----- Cleaning Workspace -----"
        cleanWs()
      }
      failure {
        echo "----- FAILURE -----"
        mail (to: "${config.emailIDs}",
        subject: "Pipeline failure: '${env.JOB_NAME}' (#${env.BUILD_ID})",
        body: "View the project build log <a href=\"${env.BUILD_URL}console\"here</a>",
        mimeType: 'text/html')
      }

      success {
        echo "----- SUCCESS -----"
        mail (to: "${config.emailIDs}",
        subject: "Pipeline success: '${env.JOB_NAME}' (#${env.BUILD_ID})",
        body: "View the project build log <a href=\"${env.BUILD_URL}console\"here</a>",
        mimeType: 'text/html')
      }
    }
  }
}