import groovy.json.JsonSlurper
import groovy.json.JsonOutput
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


def call(String giturl, String gitBranch, String serviceName, String artRepoName){
    node {
        def server = Artifactory.server 'jfrog-art'
        def rtMaven = Artifactory.newMavenBuild()
        def buildInfo = Artifactory.newBuildInfo()
        def SONAR_HOST_URL = 'http://192.168.110.71:9000'
        def sonarTotal
        def RELEASE_VERSION = '1.0.0'

        stage ('Clone') {
            //withCredentials([usernameColonPassword(credentialsId: 'gitlab', variable: 'gitlab_token')]) {
            //    echo "${gitlab_token}"
            //    git branch: gitBranch, credentialsId: "${gitlab_token}", url: giturl
            //}
            git branch: gitBranch, credentialsId: 'gitlab', url: giturl
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
            rtMaven.deployer.addProperty("JiraUrl", "http://jira.bjbryy.cn:18080//browse/" + requirements)
        }

        stage ('Artifactory configuration') {
            rtMaven.tool = 'maven-3.6.3' //Tool name from Jenkins configuration
            rtMaven.deployer releaseRepo: artRepoName, snapshotRepo: artRepoName, server: server
            rtMaven.resolver releaseRepo: artRepoName, snapshotRepo: artRepoName, server: server
        }


        stage('Sonar Scan'){
            def scannerHome = tool 'sonar-scanner'
            withSonarQubeEnv('sonarqube'){
                sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${JOB_NAME} -Dsonar.sources=./${serviceName} -Dsonar.java.binaries=* "
            }
        }

        stage("Add SonarResult"){
            //获取sonar扫描结果
            def getSonarIssuesCmd = "curl  GET -v ${SONAR_HOST_URL}/api/issues/search?componentKeys=${JOB_NAME}";
            echo "getSonarIssuesCmd:"+getSonarIssuesCmd
            process = [ 'bash', '-c', getSonarIssuesCmd].execute().text

            //增加sonar扫描结果到artifactory
            def jsonSlurper = new JsonSlurper()
            def issueMap = jsonSlurper.parseText(process);
            echo "issueMap:"+issueMap
            echo "Total:"+issueMap.total
            sonarTotal =  issueMap.total
            rtMaven.deployer.addProperty("qa.sonar.issues", "${sonarTotal}")
            
        }
        
        stage('Generate Release Version'){
            if( sonarTotal < 4 ) {
                def jarVersion = "${RELEASE_VERSION}"
                def descriptor = Artifactory.mavenDescriptor()
                descriptor.version = jarVersion
                descriptor.failOnSnapshot = false
                descriptor.transform()
            }
        }
        
        //执行maven构建Release包
        stage('Release Maven Build'){
            def pomPath = serviceName+"/pom.xml"
            sh "find . -name pom.xml \|xargs -i sed -i 's/1.0.0-SNAPSHOT/${RELEASE_VERSION}/g' {}"
            sh "find . -name pom.xml \|xargs -i cat {}"
            buildInfo.name = '1.0.0 version release'
            buildInfo.env.capture = true

            rtMaven.resolver server: server, releaseRepo: artRepoName, snapshotRepo: artRepoName
            rtMaven.deployer server: server, releaseRepo: artRepoName, snapshotRepo: artRepoName

            rtMaven.tool = 'maven-3.6.3'
            
            rtMaven.run pom: pomPath, goals: 'clean install', buildInfo: buildInfo

            def config = """{
                        "version": 1,
                        "issues": {
                                "trackerName": "JIRA",
                                "regexp": "#([\\w\\-_\\d]+)\\s(.+)",
                                "keyGroupIndex": 1,
                                "summaryGroupIndex": 2,
                                "trackerUrl": "http://jira.bjbryy.cn:18080/browse/",
                                "aggregate": "true",
                                "aggregationStatus": "Released"
                        }
                    }"""


            //buildInfo.issues.collect(server, config)

            server.publishBuildInfo buildInfo

        }

    }
}
