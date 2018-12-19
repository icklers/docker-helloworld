node('docker') {
    String helmParams
    String tagLatest
    String dockerRegistryName = "biscr"
    String dockerRegistryUrl = "biscr.azurecr.io"
    String httpProxy = "fra1.sme.zscalertwo.net:9480"
    
    if("${env.HELM_PARAMETERS}" != "") {
        helmParams = "--set ${env.HELM_PARAMETERS}"
        println(helmParams)
    } else {
        helmParams = ""
    }
    
    if("${env.TAG_LATEST}" == "true") {
        tagLatest = "-t $dockerRegistryUrl/${env.K8S_NAMESPACE}/${env.APPNAME}:latest"
        println(tagLatest)
    } else {
        tagLatest = ""
    }
    
    stage('Clean Workspace') {
        cleanWs()
    }
    
    withCredentials([azureServicePrincipal('7fce58a0-9638-45d9-8c78-f38180a5f82b')]) {
        stage('Prepare Environment') {
            sh """
            export HTTP_PROXY=${httpProxy}; HTTPS_PROXY=${httpProxy}
            az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET -t $AZURE_TENANT_ID
            az account set -s $AZURE_SUBSCRIPTION_ID
            az aks get-credentials -g ${env.AZURE_RESOURCEGROUP} -n ${env.K8S_CLUSTERNAME}
            az acr login -n $dockerRegistryName
            """
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
          dir("${env.CONTAINER_NAME}"){
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'f51eecaf-84a6-4c96-8181-2720a129e121', url: "${env.GIT_URL}"]]])
          }
        }
        
        stage('Build Docker image and push to registry') {
          dir("${env.CONTAINER_NAME}"){
            if ("${env.BUILD_ON_AZURE}" == "true") {
                // Build on Azure via acr
                sh "az acr build --registry biscr -t ${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID} ."
            } else {
                // Build locally
                // Build args for alpine images: proxy variables have to start with http://
                sh """
                    docker build --build-arg HTTP_PROXY=http://${httpProxy} --build-arg HTTPS_PROXY=http://${httpProxy} -t $dockerRegistryUrl/${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID} $tagLatest .
                    docker push $dockerRegistryUrl/${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID}
                    """
            }
          }
        }   
        
        stage('Package and upload Helm Chart') {
            if (fileExists("${WORKSPACE}/staging")) {  
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm package --version ${env.BUILD_ID} --destination ${WORKSPACE}/staging ${WORKSPACE}/${env.CONTAINER_NAME}/helm/${env.APPNAME}"
                sh "az acr helm push -n biscr ${WORKSPACE}/staging/${env.APPNAME}-${env.BUILD_ID}.tgz"
            } else {
                sh "mkdir ${WORKSPACE}/staging"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm package --version ${env.BUILD_ID} --destination ${WORKSPACE}/staging ${WORKSPACE}/${env.CONTAINER_NAME}/helm/${env.APPNAME}"
                sh "az acr helm push -n biscr ${WORKSPACE}/staging/${env.APPNAME}-${env.BUILD_ID}.tgz"
            }
        }
        stage('Run Helm Chart') {
            withEnv(["PATH+EXTRA=${WORKSPACE}/helm-${env.HELM_VERSION}"]) {
                sh "kubectl config use-context ${env.K8S_CLUSTERNAME}"
                sh "echo env path: ${PATH}"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm init --upgrade --wait --service-account tiller"
                // add our helm repo and deploy
                sh "az acr helm repo add -n biscr"
                sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm upgrade --install --debug --wait --recreate-pods --kube-context ${K8S_CLUSTERNAME} --namespace ${env.K8S_NAMESPACE} --set image.repository=biscr.azurecr.io/${env.K8S_NAMESPACE}/${env.APPNAME},image.tag=${env.BUILD_ID} $helmParams ${env.HELM_RELEASE} biscr/${env.APPNAME}"
                
                /***
                 * Workaround - use helm chart from build directory
                 * 
                dir("${env.WORKSPACE}/${env.CONTAINER_NAME}/helm/${env.APPNAME}"){
                    sh "kubectl config current-context"
                    sh "${WORKSPACE}/helm-${env.HELM_VERSION}/helm upgrade --install --debug --wait --recreate-pods --kube-context ${K8S_CLUSTERNAME} --namespace ${env.K8S_NAMESPACE} --set image.repository=biscr.azurecr.io/${env.K8S_NAMESPACE}/${env.APPNAME},image.tag=${env.BUILD_ID} $helmParams ${env.HELM_RELEASE} ."
                    //sh "echo ${WORKSPACE}/helm-${env.HELM_VERSION}/helm upgrade --install --set image.repository=biscr.azurecr.io/${env.NAMESPACE}/${env.APPNAME},image.tag=${env.BUILD_ID} ${HELM_PARAMETERS} --namespace ${env.K8S_NAMESPACE} --name ${env.HELM_RELEASE} ."
                }
                ***/
            }
        }
        stage('Clean up workspace') {
            println("removing docker image from build host to save space")
            sh """
              docker image rm $dockerRegistryUrl/${env.K8S_NAMESPACE}/${env.APPNAME}:${env.BUILD_ID}
            """
        }
    }
}