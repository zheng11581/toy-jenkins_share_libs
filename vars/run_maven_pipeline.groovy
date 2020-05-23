//@NonCPS
def getRequirementsIds() {
    def reqIds = "";
    final changeSets = currentBuild.changeSets
    echo 'changeset count:' + changeSets.size().toString()
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            def patten = ~/#[\w\-_\d]+/;
            def matcher = (logEntry.getMsg() =~ patten);
            def count = matcher.getCount();
            for (int i = 0; i < count; i++) {
                reqIds += matcher[i].replace('#', '') + ","
            }
        }
    }
    return reqIds;
}
//@NonCPS
def getRevisionIds() {
    def reqIds = "";
    final changeSets = currentBuild.changeSets
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            reqIds += logEntry.getRevision() + ","
        }
    }
    return reqIds
}


def call(String giturl, String artRepoName){
    node {
        def server = Artifactory.server 'jfrog-art'
        def rtMaven = Artifactory.newMavenBuild()
        def buildInfo = Artifactory.newBuildInfo()
        def SONAR_HOST_URL = 'http://192.168.110.71:9000'

        stage ('Clone') {
            withCredentials([usernameColonPassword(credentialsId: 'gitlab', variable: 'gitlab_token')]) {
                echo "${gitlab_token}"
                git credentialsId: "${gitlab_token}", url: giturl, branch: 'feature_#78998' 
            }
        }

        stage('Env capture') {
            echo "收集系统变量"
            buildInfo.env.capture = true
        }
        
        stage('Add JIRA Result') {
            def requirements = getRequirementsIds();
            echo "requirements : ${requirements}" 
            def revisionIds = getRevisionIds();
            echo "revisionIds : ${revisionIds}"
            rtMaven.deployer.addProperty("project.issues", requirements).addProperty("project.revisionIds", revisionIds)
            rtMaven.deployer.addProperty("JiraUrl", "http://192.168.110.52:8080/browse/" + requirements)
        }

        stage ('Artifactory configuration') {
            rtMaven.tool = 'maven-3.6.3' //Tool name from Jenkins configuration
            rtMaven.deployer releaseRepo: artRepoName+'-dev-local', snapshotRepo: artRepoName+'-dev-local', server: server
            rtMaven.resolver releaseRepo: artRepoName+'-virtual', snapshotRepo: artRepoName+'-virtual', server: server
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
