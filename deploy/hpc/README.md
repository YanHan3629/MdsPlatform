# Singularity / Slurm migration

On-demand Singularity deployment for the isolated Tongji HPC test copy.
The local Docker deployment and its data are preserved. See `使用说明.md` for
Windows start/status/connect/stop commands and the acceptance scope.

For an existing Codex development conversation moving from local execution to
HPC, read [CODEX_HANDOFF.md](./CODEX_HANDOFF.md) first. It documents the actual
source/JAR/SIF loading paths, safe update and rollback workflow, data boundaries,
and the evidence required for subsequent development.

## Platform constraints

The platform administrator confirmed that Docker is unsupported and Singularity
is the supported alternative. Never start Docker, use sudo, or run application
workloads on a login node. Prepare archives locally; perform SIF conversion and
execution in finite Slurm jobs. Do not reserve an idle GPU while transferring data.

## Execution adapter

`slurm_exec.py` starts a Singularity payload as a step inside the current Slurm
allocation. `runtime_cli.py` implements the backend's run/inspect/events/logs/
stop/rm command contract. Unknown options fail; registry image pulls are not
permitted during execution. Register local SIF paths in `MDS_IMAGE_MANIFEST`.
Set `MDS_RUNTIME_STATE` to a private persistent audit directory and
`MDS_EVIDENCE_LOCK_DIR` to the shared evidence lock directory.

The intended backend configuration is `APP_DOCKER_BINARY` pointing to the
executable `runtime_cli.py`, with Java 17 running on the host inside the Slurm
allocation. The Docker configuration remains the default locally. The platform's
default Java 11 is insufficient; private Temurin 17.0.21 has now been installed
and tested in the project directory. `serve.py` supplies the adapter configuration
to the backend. A real application index build completed in job 3193736;
job 3193742 completed quality-gated product publication, consumer authorization,
actual receiver delivery, signed receipt retrieval and byte-for-byte package checks.

CPU and memory ceilings are enforced by Slurm steps. Process limits use a hard
RLIMIT_NPROC ceiling inherited by the payload; this counts the real Linux UID's
processes across jobs and is stricter than a per-container PID limit. Jobs asking
for no swap are rejected on a node with active swap. GPU execution requires
Slurm-assigned visible devices. No resource option is silently discarded.

Lifecycle operations currently retain working directories and state for audit.
Historical task recovery across allocations has a local regression check and
still requires integration validation. Retention cleanup is not automatic.

Verified on 2026-10-29: real Redis image conversion; Slurm memory/cpuset placement;
inherited hard process ceiling of 512; payload exit code 7; logs; cancellation.
Job 3184182 completed successfully in 5 seconds, including its intentionally
failing payload and canceled payload. These checks do not certify full platform
deployment, model inference, database migration, or application semantics.

GPU job 3184295 completed in one second on gpu4002: NVIDIA L40, driver 580.65.06,
46,068 MiB, Singularity GPU visibility and CUDA initialization passed. This was
not a vLLM inference test. CPU job 3184435 converted PostgreSQL, MinIO, mc and QA
images and installed Java 17 in 1m55s.

## Staging and integrity

`snapshot_local.py` pauses the local writers, copies PostgreSQL, S3 objects,
receipts and QA traces, and restarts the writers in a finally block. The original
data remains local. `pack_snapshot.py` explicitly supports Windows long paths;
the first short-path manifest was incomplete and is retained as audit evidence.
Never upload the old incomplete snapshot archive. Require the completed archive
and its `.verified.json` receipt. Use `--verify-existing` to independently read
back every archived member and compare its hash to the snapshot manifest.

`make_checksums.py REPORT_DIRECTORY` checks the image exports and packaging
receipt, then creates `bundle-checksums.json`. Upload archives with `upload.ps1`
(SFTP resume). An optional `-Server` can select an address observed in the user's
active connection; SSH still strictly checks the saved domain/port host key.
Do not disable host-key checks or assume a previously observed address is current.

Upload the checksum manifest to `~/packages/mds-platform/`, and the Python/shell
helpers to `~/apps/mds-platform/`. Run `convert_images.sh` and `prepare_bundle.py`
only inside finite CPU Slurm jobs. Both reject hash mismatches. Bundle extraction
validates individual snapshot files; an incomplete earlier extraction is retained
and rejected rather than overwritten. Stage and verify everything before asking
for GPU resources.

## Finite service sessions

`run_platform.sh --snapshot /share/home/u08162/backup/mds-platform/snapshot-01`
must run inside a single-node Slurm allocation. `--storage-only` restores and
checks storage, then exits; `--cpu-only` skips model/QA startup. Normal mode
requires an allocated GPU. `--duration` limits the ready session to 1–7200 seconds
(default 1800); always also set a Slurm wall-time limit, covering startup and
shutdown. Keep queue deadlines separate from allocated runtime limits.

Persistent data is under `~/data/mds-platform/`; a deployment lock prevents two
sessions opening the same database. Service logs live under `runs/JOB_ID`.
Exit stops project child processes, requests dynamic task cancellation and
retains data. Startup failure also runs cleanup. SSH disconnection does not
cancel an sbatch job: cancel an unwanted test explicitly with `scancel JOB_ID`.

Backend and storage listen on loopback. Access requires a tunnel terminating on
the allocated compute node; forwarding to the login node's localhost will not
reach these services. A user-facing tunnel/start/stop workflow is provided by
`session.ps1` and `session_control.py`. The real login,
upload, snapshot publication, index build, product publication and delivery smoke
tests have passed. Model and document QA integration have also run on an L40.
The overall Slurm allocation bounds the service stack; per-service Compose
limits have not all been reproduced. Dynamic index tasks use dedicated Slurm
steps and the stricter UID-wide process limit described above.

## Real environment preflight

Export the existing local Redis image with `docker save`, transfer `redis.tar` to
`~/packages/mds-platform/`, and copy `preflight.sh` into `~/apps/mds-platform/`.
Submit a single CPU job with a short explicit time limit and collect its log.
The script checks both real image conversion and the exact resource controls
currently requested by the index runner. A failed resource check must not be
silently ignored or reported as successful application compatibility.

The full migration must preserve task IDs, logs, exit codes, cancellation,
recovery, resource limits and evidence-worker locking. PostgreSQL, MinIO, audit
traces and delivery receipts require consistent migration copies. Stop only
project-owned processes and retain persistent data at job termination.

## Native-host configuration differences

The backend exposes `app.job-script-path` (environment `APP_JOB_SCRIPT_PATH`),
defaulting to the original Docker path. Copy the original LF `command.sh` to
`~/apps/mds-platform/command.sh`. The HPC launcher selects this path.
`DS_RECEIVER_BIND` and `EVIDENCE_BIND_HOST` allow loopback-only listeners on
shared nodes; Docker defaults remain unchanged. Stage the updated evidence
worker as `~/apps/mds-platform/evidence-worker.py` and rebuild the backend JAR.

MinIO uses `127.0.0.1:19000` both internally and in generated public URLs so
the same signed URL works through the local port 19000 tunnel. Backend 8888 is
forwarded to local 18888, preserving local Docker's existing ports.

Singularity environment files use shell-safe quoting even with `--no-eval`;
see the [SingularityCE 4.2 environment documentation](https://docs.sylabs.io/guides/4.2/user-guide/environment_and_metadata.html).
Dynamic tasks bind private persistent `/root` and `/tmp` directories instead of
relying on the small Singularity session filesystem. Index inputs still go
through the original scoped-token, pagination and SHA-256 verification.

## Task filesystem and process isolation

The candidate execution adapter requires --containall, private IPC, and
--no-mount cwd,home,hostfs,bind-paths. The initial working directory is /tmp.
Each task receives its own /root and /tmp bind directories. Only explicitly
registered read-only input or lock mounts may be added by trusted runtime code.
Host PID visibility, host IPC, privileged execution, arbitrary host mounts, and
implicit host working-directory mounts are not supported. Requests for host IPC
fail before a task is created, including GPU tasks.

Both runtime_cli.py and slurm_exec.py must come from the same verified
candidate source. Switching only the JAR does not update these helpers. Stage
and verify both helper hashes before the controlled deployment switch, retain
the previous helper pair for rollback, and run no task across that switch.

Network access uses the allocated host network so workers can reach the
authenticated backend on loopback. This isolation does not replace per-request
business authorization or grant validation. Do not put unrestricted service
credentials in an untrusted task environment. The adapter runs under the HPC
account and does not certify a production multi-tenant OS security boundary.

## Enterprise identity and administrator maintenance

The feature-04 candidate separates operation capabilities, protected management
qualifications and complete current resource grants. See
[账号与权限操作说明](./账号与权限操作说明.md) for CPU administrator initialization,
target discovery, legacy upgrade, credential recovery, reviewed views and rollback.

Deploy identity_maintenance_binding.py with serve.py and the accounts helpers.
The launcher binds its current job, instance, database, Java and JAR hash before
backend startup. Its private database environment is removed on normal exit and
startup failure. The stable non-secret instance identifier survives sessions.

Historical migration results above do not certify the current candidate or a
completed cutover. Validate actual source/SIF bindings, the identity matrix,
browser behavior and model-input evidence before formal deployment.


当前账号与受控视图候选采用经审核的运行绑定清单。首次部署、更新和回退前请按[运行绑定与发布核验](./运行绑定与发布核验.md)准备并验证 `runtime-bindings.json`；Start 不会自动安装新代码。
