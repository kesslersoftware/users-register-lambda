// Jenkins Pipeline Template for AWS Lambda Functions
// This template handles all 33 Java Lambda repositories

pipeline {
    agent any
    
    tools {
        maven 'Maven'
        jdk 'JDK-21'
    }
    
    parameters {
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip unit tests (emergency builds only)'
        )
    }

    environment {
        LAMBDA_NAME = "${env.JOB_NAME.replace('-pipeline', '')}"
        BUILD_TIMESTAMP = sh(script: 'date +"%Y%m%d-%H%M%S"', returnStdout: true).trim()
        ARTIFACT_VERSION = "${GIT_COMMIT_SHORT}-${BUILD_NUMBER}-${BUILD_TIMESTAMP}"
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
                script {
                    // Create custom Maven settings to override HTTP blocker
                    writeFile file: 'custom-settings.xml', text: '''<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <mirrors>
    <mirror>
      <id>nexus-all</id>
      <mirrorOf>*</mirrorOf>
      <url>http://host.docker.internal:8096/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <servers>
    <server>
      <id>nexus-all</id>
      <username>admin</username>
      <password>admin123</password>
    </server>
    <server>
      <id>lambda-artifacts-dev</id>
      <username>admin</username>
      <password>admin123</password>
    </server>
  </servers>
</settings>'''
                }
                sh '''
                    export JAVA_HOME="${TOOL_JDK_21}"
                    export PATH="$JAVA_HOME/bin:$PATH"
                    mvn clean package shade:shade -DskipTests -s custom-settings.xml

                    # Verify the shaded JAR was created (this is the deployable Lambda JAR)
                    if [ ! -f target/${LAMBDA_NAME}.jar ]; then
                        echo "ERROR: Lambda shaded JAR not found: target/${LAMBDA_NAME}.jar"
                        ls -la target/
                        exit 1
                    fi

                    # Create deployment package with the shaded JAR
                    mkdir -p deployment
                    cp target/${LAMBDA_NAME}.jar deployment/${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar

                    echo "✅ Lambda JAR packaged: deployment/${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar"
                    echo "📝 Version breakdown:"
                    echo "   Git commit: ${GIT_COMMIT_SHORT}"
                    echo "   Build number: ${BUILD_NUMBER}"
                    echo "   Timestamp: ${BUILD_TIMESTAMP}"
                '''

                archiveArtifacts artifacts: 'deployment/*.jar', fingerprint: true
                echo "📦 Archived: ${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar"
            }
        }

        stage('Publish to Nexus') {
            steps {
                script {
                    echo "📦 Publishing Lambda JAR to Nexus artifact repository..."

                    sh '''
                        export JAVA_HOME="${TOOL_JDK_21}"
                        export PATH="$JAVA_HOME/bin:$PATH"

                        # Deploy to Nexus using Maven deploy plugin
                        mvn deploy:deploy-file \
                            -Dfile=deployment/${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar \
                            -DgroupId=com.boycottpro.lambda \
                            -DartifactId=${LAMBDA_NAME} \
                            -Dversion=${ARTIFACT_VERSION} \
                            -Dpackaging=jar \
                            -DrepositoryId=lambda-artifacts-dev \
                            -Durl=http://host.docker.internal:8096/repository/lambda-artifacts-dev/ \
                            -s custom-settings.xml

                        echo "✅ Published ${LAMBDA_NAME}:${ARTIFACT_VERSION} to Nexus"
                        echo "🔗 View at: http://localhost:8096/#browse/browse:lambda-artifacts-dev"
                        echo "📍 Repository: lambda-artifacts-dev"
                        echo "🔗 URL: http://localhost:8096/repository/lambda-artifacts-dev/"
                    '''
                }
            }
        }
        
        stage('Verify Lambda Package') {
            steps {
                script {
                    // Verify the deployment package is valid
                    sh '''
                        echo "✅ Lambda package verification"
                        echo "   JAR file: deployment/${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar"
                        echo "   Version: ${ARTIFACT_VERSION}"
                        ls -la deployment/

                        # Basic JAR validation
                        if [ -f deployment/${LAMBDA_NAME}-${ARTIFACT_VERSION}.jar ]; then
                            echo "✅ Lambda JAR package created successfully"
                            echo "   🎯 Unique version ensures no Nexus overwrites"
                            echo "   📦 Ready for deployment to AWS Lambda"
                        else
                            echo "❌ Lambda JAR package not found"
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
            echo "✅ Lambda build completed successfully"
            echo "📦 JAR package: ${LAMBDA_NAME}-${env.ARTIFACT_VERSION}.jar"
            echo "🏷️  Version format: git-commit + build-number + timestamp"
            echo "🔗 Nexus: http://localhost:8096/#browse/browse:lambda-artifacts-dev"
        }
        failure {
            emailext (
                subject: "FAILED: Lambda Build - ${LAMBDA_NAME}",
                body: "Lambda build failed for ${LAMBDA_NAME}. Check Jenkins for details.",
                to: "dylan@kesslersoftware.com"
            )
        }
    }
}