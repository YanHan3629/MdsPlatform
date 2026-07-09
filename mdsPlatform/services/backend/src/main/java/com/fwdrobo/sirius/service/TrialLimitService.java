package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.docker.service.JsonParam;
import com.fwdrobo.sirius.docker.service.JsonParamConverter;
import com.fwdrobo.sirius.docker.service.RunSpec;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.permission.LicenseQuotaPolicy;
import com.fwdrobo.sirius.entity.permission.OrgStorageUsage;
import com.fwdrobo.sirius.entity.user.UserLicenseType;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.mapper.LicenseQuotaPolicyMapper;
import com.fwdrobo.sirius.mapper.OrgMapper;
import com.fwdrobo.sirius.mapper.OrgStorageUsageMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trial 用户限制服务：统一处理组织级配额与任务算力阈值校验。
 */
@Slf4j
@Service
public class TrialLimitService {

    private final OrgMapper orgMapper;
    private final OrgStorageUsageMapper orgStorageUsageMapper;
    private final LicenseQuotaPolicyMapper licenseQuotaPolicyMapper;
    private final JobRunMapper jobRunMapper;
    private final ObjectMapper objectMapper;

    public TrialLimitService(
            OrgMapper orgMapper,
            OrgStorageUsageMapper orgStorageUsageMapper,
            LicenseQuotaPolicyMapper licenseQuotaPolicyMapper,
            JobRunMapper jobRunMapper,
            ObjectMapper objectMapper
    ) {
        this.orgMapper = orgMapper;
        this.orgStorageUsageMapper = orgStorageUsageMapper;
        this.licenseQuotaPolicyMapper = licenseQuotaPolicyMapper;
        this.jobRunMapper = jobRunMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传前执行 Trial 配额原子预占，返回本次预占字节数。
     */
    public long reserveUploadQuota(long requestSizeBytes, long existingFileSizeBytes) {
        UUID orgId = requireCurrentOrgId();
        if (!isTrialOrg(orgId)) {
            return 0L;
        }
        if (requestSizeBytes < 0) {
            throw ExceptionUtils.badRequest("试用账号上传必须提供 Content-Length");
        }
        currentPolicy(orgId);
        long normalizedExistingSize = Math.max(0L, existingFileSizeBytes);
        long incrementalBytes = Math.max(0L, requestSizeBytes - normalizedExistingSize);
        if (incrementalBytes <= 0L) {
            return 0L;
        }
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.reserveStorageIfWithinQuota(orgId, incrementalBytes);
        if (affectedRows <= 0) {
            throw ExceptionUtils.forbidden("试用账号存储空间已满");
        }
        log.info("组织存储容量预占成功: orgId={}, deltaBytes={}", orgId, incrementalBytes);
        return incrementalBytes;
    }

    /**
     * 上传失败后回滚 Trial 预占容量。
     */
    public void rollbackReservedUploadQuota(long deltaBytes) {
        if (deltaBytes <= 0) {
            return;
        }
        UUID orgId = requireCurrentOrgId();
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.reduceUsedStorageBytes(orgId, deltaBytes);
        if (affectedRows <= 0) {
            throw ExceptionUtils.notFound("组织不存在，无法回滚预占容量");
        }
        log.info("组织存储容量预占回滚成功: orgId={}, deltaBytes={}", orgId, deltaBytes);
    }

    /**
     * 上传成功后增加已用容量。
     */
    public void addUsedStorageAfterSuccess(long deltaBytes) {
        if (deltaBytes <= 0) {
            return;
        }
        UUID orgId = requireCurrentOrgId();
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.addUsedStorageBytes(orgId, deltaBytes);
        if (affectedRows <= 0) {
            throw ExceptionUtils.notFound("组织不存在，无法更新存储容量");
        }
        log.info("组织存储容量增加成功: orgId={}, deltaBytes={}", orgId, deltaBytes);
    }

    /**
     * 删除成功后扣减已用容量。
     */
    public void reduceUsedStorageAfterSuccess(long deltaBytes) {
        if (deltaBytes <= 0) {
            return;
        }
        UUID orgId = requireCurrentOrgId();
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.reduceUsedStorageBytes(orgId, deltaBytes);
        if (affectedRows <= 0) {
            throw ExceptionUtils.notFound("组织不存在，无法更新存储容量");
        }
        log.info("组织存储容量扣减成功: orgId={}, deltaBytes={}", orgId, deltaBytes);
    }

    /**
     * 解析并校验任务资源参数（GPU/CPU/内存均为可选）。
     */
    public JobResourceLimits parseAndValidateJobResources(Map<String, Object> rawParams) {
        if (rawParams == null || rawParams.isEmpty()) {
            return new JobResourceLimits(0, 0D, 0, null);
        }
        JsonParam jsonParam;
        RunSpec runSpec;
        try {
            jsonParam = objectMapper.convertValue(rawParams, JsonParam.class);
            if (jsonParam == null) {
                jsonParam = new JsonParam();
            }
            runSpec = JsonParamConverter.toRunSpec(jsonParam);
        } catch (IllegalArgumentException ex) {
            throw ExceptionUtils.badRequest("任务资源参数格式错误: " + ex.getMessage());
        }
        double cpu = 0D;
        int memoryMb = 0;
        if (runSpec.resources != null) {
            if (runSpec.resources.cpus != null) {
                if (runSpec.resources.cpus < 0D) {
                    throw ExceptionUtils.badRequest("params.resources.cpus 不能小于0");
                }
                cpu = runSpec.resources.cpus;
            }
            if (runSpec.resources.memoryMb != null) {
                if (runSpec.resources.memoryMb < 0) {
                    throw ExceptionUtils.badRequest("params.resources.memoryMb 不能小于0");
                }
                memoryMb = runSpec.resources.memoryMb;
            }
        }
        int gpuCount = parseGpuCount(jsonParam.gpus);
        return new JobResourceLimits(gpuCount, cpu, memoryMb, jsonParam.gpus);
    }

    /**
     * Trial 用户任务算力限制校验。
     */
    public void checkTrialJobResourceLimit(JobResourceLimits limits) {
        if (limits == null) {
            return;
        }
        UUID orgId = requireCurrentOrgId();
        if (!isTrialOrg(orgId)) {
            return;
        }
        LicenseQuotaPolicy policy = currentPolicy(orgId);
        int maxGpu = requireNonNegativeInt(policy.getMaxGpu(), "max_gpu");
        BigDecimal maxCpu = requirePositiveDecimal(policy.getMaxCpu(), "max_cpu");
        int maxMemoryMb = requirePositiveInt(policy.getMaxMemoryMb(), "max_memory_mb");
        int requestedGpu = resolveRequestedGpuForPolicy(limits.gpuCount(), policy);
        if (requestedGpu > maxGpu) {
            throw ExceptionUtils.forbidden("试用账号任务 GPU 额度不足");
        }
        if (BigDecimal.valueOf(limits.cpu()).compareTo(maxCpu) > 0) {
            throw ExceptionUtils.forbidden("试用账号任务 CPU 额度不足");
        }
        if (limits.memoryMb() > maxMemoryMb) {
            throw ExceptionUtils.forbidden("试用账号任务内存额度不足");
        }
    }

    /**
     * 为 Job Run 预占组织运行时资源。
     */
    public RuntimeQuotaReservation reserveRunRuntimeQuota(UUID orgId, JobResourceLimits limits) {
        if (orgId == null) {
            throw ExceptionUtils.notFound("组织不存在，无法预占运行资源");
        }
        if (limits == null) {
            return RuntimeQuotaReservation.zero();
        }
        int gpuDelta = normalizeTrackedGpu(orgId, limits.gpuCount());
        BigDecimal cpuDelta = normalizeTrackedCpu(limits.cpu());
        int memoryMbDelta = normalizeTrackedMemory(limits.memoryMb());
        if (isZeroReservation(gpuDelta, cpuDelta, memoryMbDelta)) {
            return new RuntimeQuotaReservation(gpuDelta, cpuDelta, memoryMbDelta);
        }
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.reserveRuntimeUsageIfWithinQuota(orgId, gpuDelta, cpuDelta, memoryMbDelta);
        if (affectedRows <= 0) {
            throw ExceptionUtils.forbidden("组织运行资源超限，无法启动任务");
        }
        log.info("组织运行资源预占成功: orgId={}, gpuDelta={}, cpuDelta={}, memoryMbDelta={}",
                orgId, gpuDelta, cpuDelta, memoryMbDelta);
        return new RuntimeQuotaReservation(gpuDelta, cpuDelta, memoryMbDelta);
    }

    /**
     * 释放 Job Run 预占的组织运行时资源。
     */
    public void releaseRunRuntimeQuota(UUID orgId, RuntimeQuotaReservation reservation) {
        if (reservation == null) {
            return;
        }
        releaseRunRuntimeQuota(orgId, reservation.gpu(), reservation.cpu(), reservation.memoryMb());
    }

    /**
     * 按指定值释放 Job Run 预占的组织运行时资源。
     */
    public void releaseRunRuntimeQuota(UUID orgId, Integer reservedGpu, BigDecimal reservedCpu, Integer reservedMemoryMb) {
        if (orgId == null) {
            throw ExceptionUtils.notFound("组织不存在，无法释放运行资源");
        }
        int gpuDelta = Math.max(0, reservedGpu == null ? 0 : reservedGpu);
        BigDecimal cpuDelta = (reservedCpu == null || reservedCpu.compareTo(BigDecimal.ZERO) < 0) ? BigDecimal.ZERO : reservedCpu;
        int memoryMbDelta = Math.max(0, reservedMemoryMb == null ? 0 : reservedMemoryMb);
        if (isZeroReservation(gpuDelta, cpuDelta, memoryMbDelta)) {
            return;
        }
        ensureUsageRow(orgId);
        int affectedRows = orgStorageUsageMapper.reduceRuntimeUsage(orgId, gpuDelta, cpuDelta, memoryMbDelta);
        if (affectedRows <= 0) {
            throw ExceptionUtils.notFound("组织不存在，无法释放运行资源");
        }
        log.info("组织运行资源释放成功: orgId={}, gpuDelta={}, cpuDelta={}, memoryMbDelta={}",
                orgId, gpuDelta, cpuDelta, memoryMbDelta);
    }

    /**
     * 释放单次运行预占资源（幂等）。
     */
    public void releaseRunRuntimeQuotaIfNeeded(JobRun jobRun) {
        if (jobRun == null || jobRun.getRunId() == null || jobRun.getOrgId() == null) {
            return;
        }
        int affectedRows = jobRunMapper.markRuntimeQuotaReleased(jobRun.getRunId());
        if (affectedRows <= 0) {
            return;
        }
        releaseRunRuntimeQuota(
                jobRun.getOrgId(),
                jobRun.getReservedGpu(),
                jobRun.getReservedCpu(),
                jobRun.getReservedMemoryMb()
        );
    }

    /**
     * 获取当前组织任务运行时长限制（秒）。
     */
    public int currentRuntimeLimitSeconds() {
        LicenseQuotaPolicy policy = currentPolicy(requireCurrentOrgId());
        return requirePositiveInt(policy.getMaxRuntimeSeconds(), "max_runtime_seconds");
    }

    /**
     * 获取当前用户所属组织ID。
     */
    public UUID currentOrgId() {
        return requireCurrentOrgId();
    }

    /**
     * 查询当前组织授权类型。
     */
    public String currentOrgLicenseType() {
        return currentLicenseType(requireCurrentOrgId());
    }

    /**
     * 查询当前组织对应的启用配额策略。
     */
    public LicenseQuotaPolicy currentOrgQuotaPolicy(UUID orgId) {
        return currentPolicy(orgId);
    }

    /**
     * 查询指定组织资源用量。
     */
    public OrgStorageUsage currentOrgStorageUsage(UUID orgId) {
        ensureUsageRow(orgId);
        OrgStorageUsage usage = orgStorageUsageMapper.selectByOrgId(orgId);
        if (usage == null) {
            throw ExceptionUtils.notFound("组织不存在，无法读取资源用量");
        }
        if (usage.getUsedStorageBytes() == null) {
            usage.setUsedStorageBytes(0L);
        }
        if (usage.getUsedGpu() == null) {
            usage.setUsedGpu(0);
        }
        if (usage.getUsedCpu() == null || usage.getUsedCpu().compareTo(BigDecimal.ZERO) < 0) {
            usage.setUsedCpu(BigDecimal.ZERO);
        }
        if (usage.getUsedMemoryMb() == null) {
            usage.setUsedMemoryMb(0);
        }
        return usage;
    }

    /**
     * 获取 Trial 组织识别结果。
     */
    public boolean isTrialOrg(UUID orgId) {
        return UserLicenseType.fromNullable(currentLicenseType(orgId)) == UserLicenseType.TRIAL;
    }

    /**
     * 获取当前已用容量（字节）。
     */
    public long currentUsedStorageBytes(UUID orgId) {
        ensureUsageRow(orgId);
        Long usedStorageBytes = orgStorageUsageMapper.selectUsedStorageBytes(orgId);
        if (usedStorageBytes == null) {
            throw ExceptionUtils.notFound("组织不存在，无法读取已用存储容量");
        }
        return Math.max(0L, usedStorageBytes);
    }

    /**
     * 获取当前用户所属组织ID。
     */
    private UUID requireCurrentOrgId() {
        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw ExceptionUtils.notFound("当前用户未绑定组织，无法读取配额策略");
        }
        return orgId;
    }

    /**
     * 确保存储用量行存在。
     */
    private void ensureUsageRow(UUID orgId) {
        orgStorageUsageMapper.initIfAbsent(orgId);
    }

    /**
     * 读取当前组织对应的启用策略。
     */
    private LicenseQuotaPolicy currentPolicy(UUID orgId) {
        String licenseType = currentLicenseType(orgId);
        return allPolicies().stream()
                .filter(policy -> licenseType.equals(policy.getLicenseType()))
                .findFirst()
                .orElseThrow(() -> ExceptionUtils.internalError("未找到启用中的配额策略: licenseType=" + licenseType));
    }

    /**
     * 查询全部启用中的配额策略。
     */
    public List<LicenseQuotaPolicy> allPolicies() {
        List<LicenseQuotaPolicy> policies = licenseQuotaPolicyMapper.selectAllActivePolicies();
        if (policies == null || policies.isEmpty()) {
            throw ExceptionUtils.internalError("未找到任何启用中的配额策略");
        }
        return policies;
    }

    /**
     * 查询组织授权类型。
     */
    private String currentLicenseType(UUID orgId) {
        String licenseType = orgMapper.selectLicenseTypeByOrgId(orgId);
        if (licenseType == null) {
            throw ExceptionUtils.notFound("组织不存在，无法识别授权类型");
        }
        return UserLicenseType.fromNullable(licenseType).name();
    }

    /**
     * 校验非负整型策略项。
     */
    private int requireNonNegativeInt(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw ExceptionUtils.internalError("配额策略配置非法: " + fieldName);
        }
        return value;
    }

    /**
     * 校验正整型策略项。
     */
    private int requirePositiveInt(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw ExceptionUtils.internalError("配额策略配置非法: " + fieldName);
        }
        return value;
    }

    /**
     * 校验正数策略项。
     */
    private BigDecimal requirePositiveDecimal(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw ExceptionUtils.internalError("配额策略配置非法: " + fieldName);
        }
        return value;
    }

    /**
     * 判断预占值是否全为0。
     */
    private boolean isZeroReservation(int gpuDelta, BigDecimal cpuDelta, int memoryMbDelta) {
        return gpuDelta <= 0
                && (cpuDelta == null || cpuDelta.compareTo(BigDecimal.ZERO) <= 0)
                && memoryMbDelta <= 0;
    }

    /**
     * 将请求的 GPU 数按策略归一化（支持 gpus=all）。
     */
    private int resolveRequestedGpuForPolicy(int gpuCount, LicenseQuotaPolicy policy) {
        if (gpuCount == Integer.MAX_VALUE) {
            return requireNonNegativeInt(policy.getMaxGpu(), "max_gpu");
        }
        return Math.max(0, gpuCount);
    }

    /**
     * 归一化可追踪 GPU 占用。
     */
    private int normalizeTrackedGpu(UUID orgId, int gpuCount) {
        if (gpuCount == Integer.MAX_VALUE) {
            LicenseQuotaPolicy policy = currentPolicy(orgId);
            int maxGpu = requireNonNegativeInt(policy.getMaxGpu(), "max_gpu");
            log.info("检测到 gpus=all，按组织策略最大GPU预占: orgId={}, maxGpu={}", orgId, maxGpu);
            return maxGpu;
        }
        return Math.max(0, gpuCount);
    }

    /**
     * 归一化可追踪 CPU 占用。
     */
    private BigDecimal normalizeTrackedCpu(double cpu) {
        if (cpu <= 0D) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(cpu);
    }

    /**
     * 归一化可追踪内存占用。
     */
    private int normalizeTrackedMemory(int memoryMb) {
        return Math.max(0, memoryMb);
    }

    /**
     * 解析 GPU 字段。
     */
    private int parseGpuCount(String rawGpu) {
        if (rawGpu == null || rawGpu.isBlank()) {
            return 0;
        }
        String normalizedGpu = rawGpu.trim().toLowerCase();
        if ("all".equals(normalizedGpu)) {
            return Integer.MAX_VALUE;
        }
        try {
            int gpuCount = Integer.parseInt(normalizedGpu);
            if (gpuCount < 0) {
                throw ExceptionUtils.badRequest("params.gpus 不能小于0");
            }
            return gpuCount;
        } catch (NumberFormatException ex) {
            throw ExceptionUtils.badRequest("params.gpus 仅支持数字或 all");
        }
    }

    /**
     * 任务资源参数解析结果。
     */
    public record JobResourceLimits(int gpuCount, double cpu, int memoryMb, String rawGpu) {
    }

    /**
     * Job Run 运行资源预占记录。
     */
    public record RuntimeQuotaReservation(int gpu, BigDecimal cpu, int memoryMb) {
        public static RuntimeQuotaReservation zero() {
            return new RuntimeQuotaReservation(0, BigDecimal.ZERO, 0);
        }
    }

    /**
     * 组装资源配额视图，将 used/max 合并到同一层级。
     */
    public Map<String, Object> buildResourceQuota(OrgStorageUsage usage, LicenseQuotaPolicy policy) {
        Map<String, Object> resourceQuota = new LinkedHashMap<>();
        resourceQuota.put("storageBytes", buildUsageAndQuota(usage.getUsedStorageBytes(), policy.getStorageQuotaBytes()));
        resourceQuota.put("gpu", buildUsageAndQuota(usage.getUsedGpu(), policy.getMaxGpu()));
        resourceQuota.put("cpu", buildUsageAndQuota(usage.getUsedCpu(), policy.getMaxCpu()));
        resourceQuota.put("memoryMb", buildUsageAndQuota(usage.getUsedMemoryMb(), policy.getMaxMemoryMb()));
        return resourceQuota;
    }

    /**
     * 构建单项资源的 used/max 结构。
     */
    private Map<String, Object> buildUsageAndQuota(Object used, Object max) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("used", used);
        item.put("max", max);
        return item;
    }
}
