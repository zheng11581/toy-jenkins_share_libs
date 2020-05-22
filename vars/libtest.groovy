
def call(String giturl){
    node {
        def server = Artifactory.server 'jfrog-art'
        def rtMaven = Artifactory.newMavenBuild()
        def buildInfo = Artifactory.newBuildInfo()
        def SONAR_HOST_URL = 'http://192.168.110.71:9000'

        stage ('Clone') {
            git credentialsId: '454d1ddb-d4ed-4195-a572-4bf96fd8ad19', url: giturl




        }

        stage('Env capture') {
            echo "收集系统变量"
            buildInfo.env.capture = true
        }



        stage ('Artifactory configuration') {
            rtMaven.tool = 'maven-3.6.3' //Tool name from Jenkins configuration
            rtMaven.deployer releaseRepo: 'guide-maven-dev-local', snapshotRepo: 'guide-maven-dev-local', server: server
            rtMaven.resolver releaseRepo: 'guide-maven-virtual', snapshotRepo: 'guide-maven-virtual', server: server
        }


        stage('Sonar Scan'){
            def scannerHome = tool 'sonar-scanner'
            withSonarQubeEnv('sonarqube'){
                sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${JOB_NAME} -Dsonar.sources=. -Dsonar.java.binaries=* "
            }
        }

        stage('Collection'){
            timeout(10){
                waitForQualityGate()
            }
            withSonarQubeEnv('sonarqube') {
                surl="${SONAR_HOST_URL}/api/measures/component?component=${JOB_NAME}&metricKeys=alert_status,quality_gate_details,coverage,new_coverage,bugs,new_bugs,reliability_rating,vulnerabilities,new_vulnerabilities,security_rating,sqale_rating,test_success_density,skipped_tests,test_failures,tests,test_errors,sqale_index,sqale_debt_ratio,new_sqale_debt_ratio,duplicated_lines_density&additionalFields=metrics,periods"
                def responses=httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', ignoreSslErrors: true, url: surl
                echo "Status: "+responses.status
                echo "Content: "+responses.content
                def propssonar=readJSON text: responses.content
                if (propssonar.component.measures){
                    propssonar.component.measures.each{itm ->
                        if (itm.periods && itm.periods[0].value) {
                            name="qa.code.quality."+itm.metric
                            value=itm.periods[0].value
                            rtMaven.deployer.addProperty(name, value)
                        } else if (itm.value) {
                            name="qa.code.quality."+itm.metric
                            value=itm.value
                            rtMaven.deployer.addProperty(name, value)
                        }
                    }
                }
            }
        }

        stage ('Exec Maven') {
            rtMaven.run pom: 'pom.xml', goals: 'clean install', buildInfo: buildInfo
        }

        stage ('Publish build info') {
            server.publishBuildInfo buildInfo
        }
    }
}
