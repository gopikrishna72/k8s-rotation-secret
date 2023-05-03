#!/usr/bin/env groovy

def testCleanupAndNotify(buildStatus, channel = '#development', additionalMessage = '', ticket = false, notify_any_status = false, notify_any_branch = false, notify_any_trigger = false, notify_team_teams = 'Secrets Manager HQ' ) {
  def colors = [UNSTABLE: 'warning', FAILURE: 'danger', SUCCESS: 'good']
  def slackTicketLink = ""
  def startedByUser = currentBuild.buildCauses ==~ /.*UserIdCause.*/
  println("In testCleanupAndNotify 1 !!!")
  // required for access to ConjurOps on Azure (is a No-OP for EC2 Agents)
  grantIPAccess()
  println("In testCleanupAndNotify 2 !!!")
  println("${env.JOB_NAME} #${env.BUILD_NUMBER} ${buildStatus}")
  println("------------------------")
  println(createTeamsMessage("#jenkins", colors[buildStatus], 'Secrets Manager HQ'))
  //sendNotification(
  //  color: colors[buildStatus],
  //  channel: "#jenkins",
  //  message: "${env.JOB_NAME} #${env.BUILD_NUMBER} ${buildStatus} Robs Test Message (<${env.BUILD_URL}|Open>)",
  //  teamsMessage: createTeamsMessage("#jenkins", colors[buildStatus], 'Secrets Manager HQ')
  //)
  println("------------------------")
  // For example nightly is trigger by timer 
  if (notify_any_trigger || ! startedByUser) {
    // Default is to notify only on failures scenerions
    if (notify_any_status || buildStatus in ['UNSTABLE', 'FAILURE']) {
      // Default is to notify only for master or main branches
      if (notify_any_branch || ["master", "main"].contains(env.BRANCH_NAME)) {
        if (ticket) {
          println("Generating Github Issue For Build Failure")
          // To override bashlib branch from Jenkinsfile, call bashLib.init(branch) before cleanupAndNotify.
          bashLib.init()
          slackTicketLink = github.createOrUpdateBuildFailureTicket()
        }
        println("In testCleanupAndNotify 3 !!!")
        def slackMessage = "Build <${env.BUILD_URL}|${env.JOB_NAME}#${env.BUILD_NUMBER}> has status ${buildStatus} ${slackTicketLink} Robs Test Message "
        def teamsMessage = createTeamsMessage(channel, colors[buildStatus], notify_team_teams)
        sendNotification(
          color: colors[buildStatus],
          channel: "${channel}",
          message: slackMessage,
          teamsMessage: teamsMessage
        )

        println("Sent Slack Message: ${slackMessage}")
      }
    }
  }
  println("In testCleanupAndNotify 4 !!!")
  sh '''#!/bin/bash -e
    docker_name="docker" && [[ "$NODE_LABELS" == *podman* ]] && docker_name="podman"

    [ -n "$WORKSPACE" ] && $docker_name run --rm -v ${WORKSPACE}:/workspace -w /workspace bash -c "shopt -s dotglob; rm -rf *"
  '''
  // Cleanup Azure agents, IPManager will refuse to remove the EC2 agent shared IP.
  // This will not fail the build.
  removeIPAccess()

  deleteDir()

}

// Creates a message suitable for sending to the Teams Relay using an Adaptive Card
// This gives a better display for Jenkins build results rather than just copying the exact format of
// Slack messages
def createTeamsMessage(channel, color, notify_team_teams) {
  channel = channel.startsWith('#') ? channel.minus('#') : channel
  // Map slack color names to comparable hex values for Office 365
  def colors365 = [danger: 'attention', good: 'good', warning: 'warning']
  def color365 = colors365[color]
  if (!color365) {
    color = 'accent'
  } else {
    color = color365
  }
  def cause = currentBuild.getBuildCauses().first()
  // def commit = sh(returnStdout: true, script: '''git rev-parse HEAD''')
  def commit = "4c4c977c759a49a08ab4524c4914a338111336d3"
  // get the user id
  //def userID = sh(returnStdout: true, script: '''git log -2 | grep Author| awk '{ print substr($4, 2, length($4)-2) }' | grep cyberark.com''')
  def userID = "mickeymouse@cyberark.com"
  // def userName = sh(returnStdout: true, script: '''git log -1 | grep Author| awk '{ print $2  " " $3}'''')
  def userName = "Mickey Mouse"
  println("In createTeamsMessage !!!")
  //def failedStages = getFailedStages()
  def failedStages = "No Failed Stages"
  //def status = currentBuild.currentResult
  def status = ""

  return """
{
  "team": "${notify_team_teams}",
  "channel": "${channel}",
  "subject": "Jenkins ${currentBuild.fullDisplayName} ${status}",
  "message": {
    "contentType": "application/vnd.microsoft.card.adaptive",
    "content": {
      "type": "AdaptiveCard",
      "msteams": {
        "width": "Full"
        "entities": [
           {
            "type": "mention",
             text": "<at>${user}/at>",
              "mentioned": {
                 "id": "8:orgid:xxxxxxxxxxxxxxxx",
                 "name": "XXXX YYYY"
               }
           }
      },
      "body": [
        {
          "type": "TextBlock",
          "text": "${currentBuild.fullDisplayName} ${status}",
          "wrap": true,
          "color": "${color}",
          "weight": "Bolder",
          "isSubtle": false,
          "bleed": true
        },
        {
            "type": "FactSet",
            "id": "theFacts",
            "facts": [
                {
                    "title": "Status",
                    "value": "${status}"
                },
                {
                    "title": "Build Trigger",
                    "value": "${cause.shortDescription}"
                },
                {
                  "title": "Commit",
                  "value": "${commit}"
                },
                {
                  "title": "Failed Stages",
                  "value": "${failedStages.join(', ')}"
                }
            ],
            "separator": true,
            "isVisible": false
        }
      ],
      "actions": [
        {
          "type": "Action.OpenUrl",
          "title": "View Build",
          "url": "${currentBuild.absoluteUrl}"
        },
        {
          "type": "Action.ToggleVisibility",
          "title": "Show Details",
          "targetElements": [ { "elementId": "theFacts" } ]
        }
      ]
    }
  }
}
"""
}

// Finds the display names for the stages that have been marked as FAILURE or UNSTABLE.
// This relies on BlueOcean, and has the potential to run into errors if it is embedded in a job
// rather than the library.
@NonCPS
def getFailedStages() {
  def visitor = new io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor(currentBuild.rawBuild)
  def stages = visitor.pipelineNodes.findAll{ it.type == io.jenkins.blueocean.rest.impl.pipeline.FlowNodeWrapper.NodeType.STAGE || 
    it.type == io.jenkins.blueocean.rest.impl.pipeline.FlowNodeWrapper.NodeType.PARALLEL }

  return stages.collect { stage ->
    return [
      id: stage.id,
      displayName: stage.displayName,
      result: "${stage.status.result}"
    ]
  }.findAll { it.result == 'FAILURE' || it.result == 'UNSTABLE' }.collect{ stage ->
    return stage.displayName
  }
}
def test1(){
    println("In test1 !!!")
}
def test2(){
     println("In test2 !!!")
}
return this