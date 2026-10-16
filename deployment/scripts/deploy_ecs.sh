#!/usr/bin/env bash
set -euo pipefail
umask 077

for name in AWS_REGION AWS_ACCOUNT_ID ECS_CLUSTER ECS_SERVICE ECS_TASK_FAMILY ECS_TASK_EXECUTION_ROLE_ARN ECS_TASK_ROLE_ARN FRONTEND_IMAGE USER_SERVICE_IMAGE PAYMENT_SERVICE_IMAGE IMAGE_TAG; do
  [[ -n "${!name:-}" ]] || { echo "Missing deployment variable: $name" >&2; exit 1; }
done
[[ "$AWS_REGION" == eu-central-1 && "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]]
[[ "${ECS_DESIRED_COUNT:-1}" =~ ^[1-9][0-9]*$ ]]
[[ "$IMAGE_TAG" =~ ^sha-[0-9a-f]{40}-[0-9]+-[0-9]+$ ]]
export AWS_PAGER=''
work_dir=$(mktemp -d)
new_task=''

diagnostics() {
  # Project only operational fields; never print full tasks, environments or logs.
  aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
    --query 'services[].{service:serviceName,taskDefinition:taskDefinition,desired:desiredCount,running:runningCount,deployments:deployments[].{status:status,rolloutState:rolloutState,reason:rolloutStateReason},events:events[:8].{message:message}}' --output json || true
  local stopped
  stopped=$(aws ecs list-tasks --cluster "$ECS_CLUSTER" --service-name "$ECS_SERVICE" --desired-status STOPPED --max-items 10 --query taskArns --output text) || return 0
  if [[ -n "$stopped" && "$stopped" != None ]]; then
    read -r -a task_arns <<< "$stopped"
    aws ecs describe-tasks --cluster "$ECS_CLUSTER" --tasks "${task_arns[@]}" \
      --query 'tasks[].{task:taskArn,stoppedReason:stoppedReason,containers:containers[].{name:name,exitCode:exitCode,reason:reason,health:healthStatus}}' --output json || true
  fi
}

finish() {
  local status=$?
  if (( status != 0 )); then
    echo 'ECS deployment failed; diagnostic fields follow (no secret values).' >&2
    diagnostics
  fi
  rm -rf "$work_dir"
  exit "$status"
}
trap finish EXIT

aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" > "$work_dir/service.json"
old_task=$(python3 deployment/scripts/render_task_definition.py service "$work_dir/service.json")
aws ecs describe-task-definition --task-definition "$old_task" --include TAGS > "$work_dir/task.json"
python3 deployment/scripts/render_task_definition.py task "$work_dir/task.json" "$work_dir/new-task.json"

# Resolve each immutable tag again so mismatched artifacts cannot form a deployment.
for service in frontend user-service payment-service; do
  variable="${service^^}_IMAGE"; variable="${variable//-/_}"
  digest=$(aws ecr describe-images --repository-name "gratilo/$service" --image-ids "imageTag=$IMAGE_TAG" --query 'imageDetails[0].imageDigest' --output text)
  [[ "${!variable}" == "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/gratilo/$service@$digest" ]]
done

new_task=$(aws ecs register-task-definition --cli-input-json "file://$work_dir/new-task.json" --query 'taskDefinition.taskDefinitionArn' --output text)
[[ "$new_task" == "arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT_ID:task-definition/$ECS_TASK_FAMILY:"* ]]
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  { echo "Version: $IMAGE_TAG"; echo "Previous task definition (rollback): $old_task"; echo "Requested task definition: $new_task"; } >> "$GITHUB_STEP_SUMMARY"
fi
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$new_task" \
  --desired-count "${ECS_DESIRED_COUNT:-1}" --query 'service.serviceArn' --output text
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE"
aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" > "$work_dir/result.json"
python3 - "$work_dir/result.json" "$new_task" <<'PY'
import json, sys
service = json.load(open(sys.argv[1]))['services'][0]
deployments = service.get('deployments', [])
success = (service['taskDefinition'] == sys.argv[2] and service['desiredCount'] > 0
           and service['runningCount'] == service['desiredCount'] and len(deployments) == 1
           and deployments[0]['taskDefinition'] == sys.argv[2]
           and deployments[0].get('rolloutState') == 'COMPLETED')
if not success:
    sys.exit('New revision did not complete successfully; a stable rollback is not a successful deployment.')
print('All three containers deployed in the requested ECS task revision.')
PY
