(function () {
  'use strict';
  const labels = {
    ACTIVE:'启用', ARCHIVED:'已归档', PENDING:'待处理', PUBLISHED:'已发布', NOT_PUBLISHED:'未发布',
    READY:'已就绪', NOT_BUILT:'未构建', RAW_READY:'原始资料已就绪', DRAFT:'草稿', NO_VERSION:'暂无版本',
    UPLOADING:'上传中', INDEXING:'索引构建中', BUILDING:'构建中', PREWARMING:'索引预热中', QUEUED:'排队中',
    RUNNING:'处理中', CANCELED:'已取消', CANCELLED:'已取消', CANCELING:'取消中',
    SUCCEEDED:'已完成', COMPLETED:'已完成', SUCCESS:'成功', FAILED:'失败', DEPRECATED:'已停用',
    APPROVED:'已批准', REJECTED:'已拒绝', REVOKED:'已撤销', EXPIRED:'已到期', SUBMITTED:'已提交',
    CONFIRMED:'已确认', CONTRACTED:'已签约', CONFLICT:'有冲突', SIGNED:'已签收', DELIVERED:'已交付',
    PENDING_CONFIRMATION:'待确认条款', WAITING_VALIDITY:'待授权生效', EFFECTIVE:'已生效',
    FULL_VERSION:'完整冻结版本', CONTROLLED_VIEW:'受控视图', LOCAL_VERIFIED:'本地核验接收端',
    NOT_SCHEDULED:'尚未安排', SCHEDULED:'已安排', RETRY:'待重试', REQUEUED:'已重新排队', WAITING_INDEX:'等待索引就绪',
    INDEX_AUTHORITY_DENIED:'索引授权失效', INDEX_EXECUTION_FAILED:'索引任务执行失败',
    INDEX_WORKER_FAILED:'索引工作器处理失败', INDEX_PRELOAD_AUTHORITY_OR_LOADING_FAILED:'索引预热或授权校验失败',
    ASSOCIATE_EXISTING_DATASET:'关联现有数据集', CONFIGURE_LOCAL_RECEIVER:'配置本地接收端',
    CONFIRM_TECHNICAL_CONTRACT:'确认技术合约', CREATE_SPACE:'创建空间', DECIDE_APPLICATION:'审核消费申请',
    DELIST_PRODUCT:'下架产品', PROPOSE_TECHNICAL_CONTRACT:'拟定技术合约', PUBLISH_PRODUCT_VERSION:'发布产品版本',
    RELIST_FROZEN_PRODUCT:'重新上架冻结产品', REQUEST_DELIVERY_RETRY:'申请重试交付', REQUEST_INDEX:'请求构建索引',
    REVISE_TECHNICAL_CONTRACT:'修订技术合约', REVOKE_TECHNICAL_CONTRACT:'撤销技术合约',
    SUBMIT_APPLICATION:'提交消费申请', UPDATE_CATALOG:'更新目录',
    DESCRIBED:'已维护目录', ASSOCIATED:'已关联', NEEDS_METADATA:'待补充业务资料',
    OWNER:'本企业管理', GRANTED:'已获授权', PRIVATE:'私有', INTERNAL:'企业内可见', PUBLIC:'已登录企业可发现',
    PENDING_AUTH:'待审核', VERIFIED:'已认证', NOT_ASSESSED:'未评估', PARTIAL:'部分评估',
    PASSED:'通过', PASS:'通过', NOT_APPLICABLE:'不适用', GENERAL:'通用', MIXED:'多种格式',
    IMAGE_TEXT:'图文', IMAGE:'图像', TEXT:'文本', VIDEO:'视频', AUDIO:'音频', AUDIO_TEXT:'音频与文本',
    TABLE:'表格', DOCUMENT:'文档', TIMESERIES:'时序', MODEL_3D:'三维模型',
    EMPTY:'暂无文件', AVAILABLE:'可用'
  };
  window.mdsLabel = (value, fallback = '待确认') => Object.hasOwn(labels, value) ? labels[value] : fallback;
  window.mdsSystemMessage = (value, fallback = '后台操作未完成。') => {
    const message = value == null ? '' : String(value).trim();
    if (!message) return '';
    if (Object.hasOwn(labels,message)) return labels[message];
    const containsTrace = /\b(?:[\w.$]*(?:Exception|Error)|Traceback|StackTrace|stack trace)\b|(?:^|\n)\s*at\s+[\w.$]+\(/i.test(message);
    return /^\p{Script=Han}/u.test(message) && !containsTrace ? message : fallback;
  };
  window.mdsErrorMessage = (error, messages = {}, fallback = '操作未完成，请刷新记录后重试。') => {
    const mapped = Object.hasOwn(messages,error?.code) ? messages[error.code] : null;
    const text = mapped || window.mdsSystemMessage(error?.message,fallback) || fallback;
    const id = error?.requestId;
    return typeof id === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id) && !text.includes(id)
      ? `${text} 请求编号 ${id}` : text;
  };
  window.mdsOrganizationName = identity => {
    const name = identity?.enterpriseIdentity?.enterpriseName || identity?.orgName || identity?.org?.orgName || identity?.orgId;
    return /^Platform management [a-f0-9-]{36}$/i.test(name || '') ? '平台管理组织' : name || '未登录';
  };
  const business = {
    procurement:'供应采购', rd:'产品设计与规格', manufacturing:'生产制造', quality:'质量检验',
    warehousing:'仓储包装', transport:'运输交接', fulfilment:'订单履约', service:'售后运维', reference:'基础资料'
  };
  window.mdsBusinessLabels = Object.freeze(business);
  window.mdsDatasetName = row => {
    const name = row.displayName || row.datasetName;
    if (name === 'f04_upload_enterprise_source_518_current_bytes') return '家电产业链源数据集';
    if (/^Original human-reviewed eleven formats \d+$/.test(name)) return '十一种格式复核资料';
    const enterprise = /^Current enterprise responsibility (ENT-[A-Z0-9]+) \d+$/.exec(name);
    return enterprise ? '企业责任资料 · ' + enterprise[1] : name;
  };
  document.addEventListener('invalid', event => {
    const field=event.target, validity=field.validity;
    if(!validity || validity.customError)return;
    const message=validity.valueMissing?'请填写此项。':
      validity.tooLong||validity.tooShort?'输入长度超出允许范围。':
      validity.rangeOverflow||validity.rangeUnderflow||validity.stepMismatch?'数值超出允许范围或不符合步长。':
      '请按要求填写有效格式。';
    field.setCustomValidity(message);
    field.dataset.platformValidation='true';
  },true);
  for(const name of ['input','change','reset']){
    document.addEventListener(name,event=>{
      const fields=name==='reset'?event.target.querySelectorAll('[data-platform-validation]'):[event.target];
      for(const field of fields)if(field.dataset?.platformValidation==='true'){
        field.setCustomValidity('');
        delete field.dataset.platformValidation;
      }
    },true);
  }
  window.mdsParseJson = (text,name) => {
    try { return JSON.parse(text); }
    catch { throw new Error(name+'格式不正确，请检查括号、引号和分隔符。'); }
  };
})();
