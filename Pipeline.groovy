node {
    String helmParams
            
    if("${env.HELM_PARAMETERS}" != "") {
        helmParams = "--set ${env.HELM_PARAMETERS}"
    } else {
        helmParams = ""
    }

    withCredentials([azureServicePrincipal('7fce58a0-9638-45d9-8c78-f38180a5f82b')]) {
        stage('Prepare Environment') {
            sh 'export HTTP_PROXY=fra1.sme.zscalertwo.net:9480; HTTPS_PROXY=fra1.sme.zscalertwo.net:9480'
            sh 'az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET -t $AZURE_TENANT_ID'
            sh 'az account set -s $AZURE_SUBSCRIPTION_ID'
            sh "az aks get-credentials -g ${env.AZURE_RESOURCEGROUP} -n ${env.K8S_CLUSTERNAME}"
            println("${env.HELM_PARAMETERS}")
        }
        
        stage('Download and initialize Helm') {
            if (fileExists("${WORKSPACE}/helm-v${env.HELM_VERSION}-linux-amd64.tar.gz")) {
                sh "export PATH=$PATH:${WORKSPACE}/helm-${env.HELM_VERSION}"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm init --client-only --wait"
            } else {
                sh "export PATH=$PATH:${WORKSPACE}/helm-${env.HELM_VERSION}"
                sh "wget -c --quiet https://storage.googleapis.com/kubernetes-helm/helm-v${env.HELM_VERSION}-linux-amd64.tar.gz"
                sh "tar xfz helm-v${env.HELM_VERSION}-linux-amd64.tar.gz"
                sh "mv linux-amd64 helm-${env.HELM_VERSION}"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm init --client-only --wait"
            }
        }
        
        stage("Checkout Git repo") { // for display purposes
          dir("${env.PROJECT_NAME}"){
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'f51eecaf-84a6-4c96-8181-2720a129e121', url: "${env.GIT_URL}"]]])
          }
        }
        
        stage('Build Docker image and push to registry') {
          dir("${env.PROJECT_NAME}"){
            // sh "az acr build --registry ${acrSettings.name} -t ${acrSettings.loginServer}/${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID} -t ${acrSettings.loginServer}/${env.K8S_NAMESPACE}/${env.APPNAME}:latest ."
            sh "az acr build --registry biscr -t ${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID} ."
          }
        }   
        
        stage('Package and upload Helm Chart') {
            if (fileExists("${WORKSPACE}/staging")) {  
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm package --version ${env.BUILD_ID} --destination ${WORKSPACE}/staging ${WORKSPACE}/${env.PROJECT_NAME}/helm/${env.APPNAME}"
                sh "az acr helm push -n biscr ${WORKSPACE}/staging/${env.APPNAME}-${env.BUILD_ID}.tgz"
            } else {
                sh "mkdir ${WORKSPACE}/staging"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm package --version ${env.BUILD_ID} --destination ${WORKSPACE}/staging ${WORKSPACE}/${env.PROJECT_NAME}/helm/${env.APPNAME}"
                sh "az acr helm push -n biscr ${WORKSPACE}/staging/${env.APPNAME}-${env.BUILD_ID}.tgz"
            }
        }
        stage('Run Helm Chart') {
            sh "kubectl config use-context ${env.K8S_CLUSTERNAME}"
            
            sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm init --upgrade --wait --service-account tiller"
            /* This currently fails, because azure-cli can't find helm... need to work on that
             * Workaround is to use the checked out helm chart in the workspace.
            sh "az acr helm repo add -n biscr"
            sh "echo ${WORKSPACE}/helm-${env.HELM_VERSION}/helm install --namespace ${env.K8S_NAMESPACE} --set ${env.HELM_OPTIONS} --name ${env.HELM_RELEASE} biscr/${env.APPNAME}"
            */
            
            dir("${env.WORKSPACE}/${env.PROJECT_NAME}/helm/${env.APPNAME}"){
                sh "kubectl config current-context"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm upgrade --install --debug --wait --recreate-pods --kube-context ${K8S_CLUSTERNAME} --namespace ${env.K8S_NAMESPACE} --set image.repository=biscr.azurecr.io/${env.K8S_NAMESPACE}/${env.APPNAME},image.tag=${env.BUILD_ID} $helmParams ${env.HELM_RELEASE} ."
            }
        }
        stage('Clean up workspace') {
            println("Cleaning up ")
            sh "rm -rf ${WORKSPACE}/*"
        }
    }
}