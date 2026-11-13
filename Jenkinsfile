// Trigger: push to main -> GitHub webhook -> local Jenkins builds the JAR -> deploys to VPS (jar-only, PM2).
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        JAR_NAME      = 'sara-whatsapp-service-0.0.1-SNAPSHOT.jar'
        DEPLOY_DIR    = '/root/sara-whatsapp-service'
        PM2_APP_NAME  = 'sara-whatsapp-service'
        // Same credentials as the other Sara jobs (same VPS).
        VPS_SSH_CRED  = 'sara-vps-ssh'    // kind: "SSH Username with private key"
        VPS_HOST_CRED = 'sara-vps-host'   // kind: "Secret text", the VPS IP or hostname
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh './mvnw test -B'
            }
        }

        stage('Build JAR') {
            steps {
                sh './mvnw package -DskipTests -B'
            }
        }

        stage('Deploy to VPS') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: env.VPS_SSH_CRED,
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'VPS_USER'
                    ),
                    string(credentialsId: env.VPS_HOST_CRED, variable: 'VPS_HOST')
                ]) {
                    sh """
                        set -e
                        SSH="ssh -o StrictHostKeyChecking=no -i \$SSH_KEY \$VPS_USER@\$VPS_HOST"
                        SCP="scp -o StrictHostKeyChecking=no -i \$SSH_KEY"

                        \$SSH 'mkdir -p ${DEPLOY_DIR}/target/incoming'
                        \$SCP target/*.jar \$VPS_USER@\$VPS_HOST:${DEPLOY_DIR}/target/incoming/
                        \$SSH '
                            set -e
                            cd ${DEPLOY_DIR}/target
                            NEW_JAR=\$(ls incoming/*.jar | head -n1)
                            mv -f "\$NEW_JAR" ${JAR_NAME}
                            rmdir incoming 2>/dev/null || true
                            if pm2 describe ${PM2_APP_NAME} > /dev/null 2>&1; then
                                pm2 restart ${PM2_APP_NAME} --update-env
                            else
                                cd ${DEPLOY_DIR} && pm2 start doppler --name ${PM2_APP_NAME} --interpreter none -- run -- java -jar ${DEPLOY_DIR}/target/${JAR_NAME}
                            fi
                            pm2 save
                        '
                    """
                }
            }
        }
    }

    post {
        failure {
            echo 'Build or deploy failed. Check the stage logs above.'
        }
    }
}
