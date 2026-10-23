#!/usr/bin/env bash
set -euo pipefail
umask 077

for name in AWS_REGION AWS_ACCOUNT_ID ECS_CLUSTER ECS_SERVICE ECS_TASK_FAMILY ECS_TASK_EXECUTION_ROLE_ARN ECS_TASK_ROLE_ARN FRONTEND_IMAGE USER_SERVICE_IMAGE PAYMENT_SERVICE_IMAGE IMAGE_TAG STRIPE_TEST_MODE; do
  [[ -n "${!name:-}" ]] || { echo "Missing deployment variable: $name" >&2; exit 1; }
done
[[ "$AWS_REGION" == eu-central-1 && "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]]
[[ "$STRIPE_TEST_MODE" == true || "$STRIPE_TEST_MODE" == false ]] || { echo 'STRIPE_TEST_MODE must be explicitly true or false.' >&2; exit 1; }
if [[ "$STRIPE_TEST_MODE" == true ]]; then
  [[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch && "${GITHUB_REF:-}" == refs/heads/main && "${STRIPE_TEST_CONFIRMATION:-}" == 'ENABLE STRIPE TEST PAYMENTS' ]] || {
    echo 'Stripe test deployment requires manual main-branch approval and its dedicated confirmation.' >&2; exit 1;
  }
fi
if [[ "${STRIPE_TEST_PAYOUTS_ENABLED:-false}" == true ]]; then
  [[ "$STRIPE_TEST_MODE" == true && "${STRIPE_TEST_PAYOUT_CONFIRMATION:-}" == 'ENABLE STRIPE TEST PAYOUTS' ]] || {
    echo 'External test payouts require Stripe test mode and their separate confirmation.' >&2; exit 1;
  }
fi
[[ "${ECS_DESIRED_COUNT:-1}" =~ ^[1-9][0-9]*$ ]]
[[ "$IMAGE_TAG" =~ ^sha-[0-9a-f]{40}-[0-9]+-[0-9]+$ || "$IMAGE_TAG" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
export AWS_PAGER=''
work_dir=$(mktemp -d)
new_task=''
old_task=''
deployment_attempted=false

rollback_deployment() {
  # Never overwrite an unrelated revision deployed concurrently outside Actions.
  aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" > "$work_dir/rollback-service.json" || return 1
  local current
  current=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["services"][0]["taskDefinition"])' "$work_dir/rollback-service.json") || return 1
  if [[ "$current" != "$old_task" && "$current" != "$new_task" ]]; then
    echo 'Rollback refused: service points at an unrelated revision; operator intervention required.' >&2
    return 1
  fi
  if [[ "$current" != "$old_task" ]]; then
    aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$old_task" \
      --desired-count "$old_count" --query 'service.serviceArn' --output text || return 1
  fi
  aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" || return 1
  python3 deployment/scripts/wait_ecs_rollout.py "$old_task" || return 1
  echo "Rollback service stabilized; verifying previous revision: $old_task"
  python3 deployment/scripts/verify_ecs_health.py "$old_task" || return 1
  echo "Rollback verified: $old_task"
}

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
    if [[ "$deployment_attempted" == true ]]; then
      if rollback_deployment; then
        echo "Rollback succeeded; deployment remains FAILED." >&2
        [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "FAILED — rollback verified: $old_task" >> "$GITHUB_STEP_SUMMARY"
      else
        echo "ROLLBACK NOT VERIFIED — operator intervention required; previous revision: $old_task" >&2
        [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "FAILED — rollback not verified; previous revision: $old_task" >> "$GITHUB_STEP_SUMMARY"
      fi
    fi
  fi
  rm -rf "$work_dir"
  exit "$status"
}
trap finish EXIT

aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" > "$work_dir/service.json"
old_task=$(python3 deployment/scripts/render_task_definition.py service "$work_dir/service.json")
old_count=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["services"][0]["desiredCount"])' "$work_dir/service.json")
# Probe health-query permissions before registration/update, without requiring a healthy baseline.
python3 deployment/scripts/verify_ecs_health.py "$old_task" --permissions-only
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
deployment_attempted=true
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$new_task" \
  --desired-count "${ECS_DESIRED_COUNT:-$old_count}" --query 'service.serviceArn' --output text
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE"
# The AWS waiter checks deployment/task counts, but does not require COMPLETED.
python3 deployment/scripts/wait_ecs_rollout.py "$new_task"
echo 'All three containers deployed in the requested ECS task revision.'
python3 deployment/scripts/verify_ecs_health.py "$new_task"
[[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "SUCCESS — selected revision healthy; HTTPS and both API routes verified." >> "$GITHUB_STEP_SUMMARY"
