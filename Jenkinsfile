// Jenkins Pipeline Template for AWS Lambda Functions
// This template handles all 33 Java Lambda repositories

pipeline {
    agent any
    
    tools {
        maven 'Maven'
        jdk 'JDK-21'
    }
    
    parameters {
        choice(
            name: 'DEPLOY_ENVIRONMENT',
            choices: ['dev', 'staging', 'test', 'prod'],
            description: 'Target deployment environment'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip unit tests (emergency deployments only)'
        )
    }
    
    environment {
        AWS_PROFILE = "boycottpro-${params.DEPLOY_ENVIRONMENT}-ops"
        LAMBDA_NAME = "${env.JOB_NAME.replace('-pipeline', '')}"
        REGION = "us-east-1"
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(
                        script: "git rev-parse --short HEAD",
                        returnStdout: true
                    ).trim()
                }
            }
        }
        
        stage('Test') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                sh '''
                    export JAVA_HOME="${TOOL_JDK_21}"
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn clean test
                '''
            }
            post {
                always {
                    script {
                        try {
                            junit testResultsPattern: 'target/surefire-reports/*.xml'
                            echo "✅ Test results published successfully"
                        } catch (Exception e) {
                            echo "⚠️ Failed to publish test results: ${e.getMessage()}"
                        }
                        
                        try {
                            publishHTML([
                                allowMissing: true,
                                alwaysLinkToLastBuild: true,
                                keepAll: true,
                                reportDir: 'target/site/jacoco',
                                reportFiles: 'index.html',
                                reportName: 'JaCoCo Coverage Report'
                            ])
                            echo "✅ JaCoCo coverage report published"
                        } catch (Exception e) {
                            echo "⚠️ Failed to publish JaCoCo report: ${e.getMessage()}"
                        }
                    }
                }
            }
        }
        
        stage('SonarQube Analysis') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    try {
                        withSonarQubeEnv('Local-SonarQube') {
                            sh '''
                                export JAVA_HOME="${TOOL_JDK_21}"
                                export PATH="$JAVA_HOME/bin:$PATH"
                                mvn sonar:sonar \
                                    -Dsonar.projectKey=${LAMBDA_NAME} \
                                    -Dsonar.projectName="${LAMBDA_NAME}" \
                                    -Dsonar.projectVersion=${GIT_COMMIT_SHORT}
                            '''
                        }
                        echo "✅ SonarQube analysis completed successfully"
                    } catch (Exception e) {
                        echo "⚠️ SonarQube analysis failed but continuing build: ${e.getMessage()}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        
        stage('Quality Gate (Informational Only)') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    try {
                        timeout(time: 3, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                echo "⚠️ SonarQube Quality Gate failed: ${qg.status}"
                                echo "📊 This is informational only - build will continue"
                                currentBuild.result = 'UNSTABLE'
                            } else {
                                echo "✅ SonarQube Quality Gate passed"
                            }
                        }
                    } catch (Exception e) {
                        echo "⚠️ Quality Gate check failed but continuing: ${e.getMessage()}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        
        stage('Build Lambda Package') {
            steps {
                sh '''
                    export JAVA_HOME="${TOOL_JDK_21}"
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn clean package shade:shade -DskipTests

                    # Verify the shaded JAR was created (this is the deployable Lambda JAR)
                    if [ ! -f target/${LAMBDA_NAME}.jar ]; then
                        echo "ERROR: Lambda shaded JAR not found: target/${LAMBDA_NAME}.jar"
                        ls -la target/
                        exit 1
                    fi

                    # Create deployment package with the shaded JAR
                    mkdir -p deployment
                    cp target/${LAMBDA_NAME}.jar deployment/${LAMBDA_NAME}-${GIT_COMMIT_SHORT}.jar

                    echo "✅ Lambda JAR packaged: deployment/${LAMBDA_NAME}-${GIT_COMMIT_SHORT}.jar"
                '''
                
                archiveArtifacts artifacts: 'deployment/*.jar', fingerprint: true
            }
        }
        
        stage('Deploy to AWS') {
            steps {
                deployLambda()
            }
        }
        
        stage('Integration Tests') {
            when {
                expression { params.DEPLOY_ENVIRONMENT != 'prod' }
            }
            steps {
                script {
                    // Run basic Lambda invocation test
                    sh '''
                        aws lambda invoke \
                            --function-name ${LAMBDA_NAME}-${DEPLOY_ENVIRONMENT} \
                            --payload '{"test": true}' \
                            --region ${REGION} \
                            --profile ${AWS_PROFILE} \
                            response.json
                            
                        # Check if Lambda responded
                        if [ ! -s response.json ]; then
                            echo "ERROR: Lambda did not respond"
                            exit 1
                        fi
                    '''
                }
            }
        }
    }
    
    post {
        always {
            cleanWs()
        }
        success {
            script {
                if (params.DEPLOY_ENVIRONMENT == 'prod') {
                    // Tag the successful production deployment
                    sh """
                        git tag -a prod-${env.GIT_COMMIT_SHORT} -m "Production deployment of ${LAMBDA_NAME}"
                        git push origin prod-${env.GIT_COMMIT_SHORT}
                    """
                }
            }
        }
        failure {
            emailext (
                subject: "FAILED: Lambda Deployment - ${LAMBDA_NAME}",
                body: "Lambda deployment failed for ${LAMBDA_NAME} in ${params.DEPLOY_ENVIRONMENT} environment. Check Jenkins for details.",
                to: "dylan@kesslersoftware.com"
            )
        }
    }
}

def deployLambda() {
    withAWS(profile: env.AWS_PROFILE, region: env.REGION) {
        sh """
            # Update Lambda function directly with JAR file
            aws lambda update-function-code \
                --function-name ${LAMBDA_NAME}-${params.DEPLOY_ENVIRONMENT} \
                --zip-file fileb://deployment/${LAMBDA_NAME}-${env.GIT_COMMIT_SHORT}.jar \
                --region ${env.REGION}
            
            # Wait for update to complete
            aws lambda wait function-updated \
                --function-name ${LAMBDA_NAME}-${params.DEPLOY_ENVIRONMENT} \
                --region ${env.REGION}
                
            echo "Successfully deployed ${LAMBDA_NAME} to ${env.REGION}"
        """
    }
}