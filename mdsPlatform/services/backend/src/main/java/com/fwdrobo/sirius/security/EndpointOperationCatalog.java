package com.fwdrobo.sirius.security;
import java.util.*;
/** Fixed server handler mapping. User-supplied paths or headers never add operations. */
public final class EndpointOperationCatalog {
 private EndpointOperationCatalog(){}
 private static final Map<String,String> MAPPING;
 static {
  var m=new LinkedHashMap<String,String>();
  put(m,"MmSpaceController","dataset.create","create");
  put(m,"MmSpaceController","data.catalog.read","list","detail");
  put(m,"MmDatasetController","dataset.create","createDataset");
  put(m,"MmDatasetController","data.catalog.read","listDatasets","getDataset","listVersions","getVersion");
  put(m,"MmDatasetController","dataset.draft.manage","createVersion");
  put(m,"MmDatasetController","dataset.publish","publishVersion");
  put(m,"MmAssetController","data.catalog.read","listAssets","getAsset");
  put(m,"MmAssetController","dataset.draft.manage","replaceTags","replaceCategories");
  put(m,"MmFileFacadeController","dataset.upload","beginUpload","uploadContent","beginFolderUpload","uploadFolderContent","createFolderUploadSession","beginFolderUploadSessionBatch","completeFolderUploadSessionFiles","getFolderUploadSession","completeFolderUploadSession","uploadFolderArchive");
  put(m,"MmFileFacadeController","dataset.draft.manage","deleteFile");
  put(m,"MmFileFacadeController","data.catalog.read","listFiles");
  put(m,"MmFileFacadeController","data.raw.download","downloadContent","downloadUrl");
  put(m,"MmIndexController","dataset.index.activate","activate");
  put(m,"MmIndexController","dataset.index.build","buildIndex","getIndexStatus");
  put(m,"IndexJobCallbackController","dataset.index.build","ready","failed");
  put(m,"ControlledViewPublicationController","dataset.share.manage","review","confirm","list","get","start","reconcile","revoke");
  put(m,"MmIngestTaskController","dataset.index.build","list","mapping","backfill");
  put(m,"MmSearchController","data.search","textToImage","imageToText");
  put(m,"ArtifactController","dataset.create","createArtifact");
  put(m,"ArtifactController","dataset.draft.manage","updateArtifact","deleteArtifact","createCommit","getOrCreateDraft");
  put(m,"ArtifactController","dataset.publish","publishCommit");
  put(m,"ArtifactController","data.catalog.read","getArtifact","listArtifacts","getArtifactTags","listCommits","getCommitCount","getCommit","getHead","getDraft");
  put(m,"FileController","dataset.upload","beginWrite","uploadContent");
  put(m,"FileController","dataset.draft.manage","delete");
  put(m,"FileController","data.raw.download","downloadContent","downloadContentById","download");
  put(m,"FileController","data.catalog.read","listFiles");
  put(m,"JobController","platform.runtime.manage","createJob","updateJob","run","ioManifest");
  put(m,"JobController","job.read","pageQuery","getJobById","getJobRunById","runPageQuery");
  put(m,"JobController","job.log.read","logs");
  put(m,"JobController","job.cancel","stop");
  put(m,"JobController","data.raw.download","downloadJobFile");
  put(m,"JobController","platform.runtime.manage","getCommand");
  put(m,"DockerController","platform.runtime.manage","getImages");
  put(m,"RbacController","identity.type.manage","adminOnly");
  put(m,"RbacController","identity.self.read","userAllowed");
  put(m,"DataSpaceFlowController","data.catalog.read","snapshot","listBusinessDomains","listSpaces","listSources","listCatalogs","listDatasets","listProducts","searchProducts","productScope");
  put(m,"DataSpaceFlowController","dataset.create","registerSpace");
  put(m,"DataSpaceFlowController","dataset.draft.manage","createSource");
  put(m,"DataSpaceFlowController","platform.runtime.manage","verifySpace","attachLocalDataset","runVal2017Flow","syncProductToMinio");
  put(m,"DataSpaceFlowController","dataset.upload","uploadFiles");
  put(m,"DataSpaceFlowController","dataset.draft.manage","createCatalog");
  put(m,"DataSpaceFlowController","dataset.quality.run","governSource");
  put(m,"DataSpaceFlowController","dataset.index.build","buildIndex");
  put(m,"DataSpaceFlowController","product.publish","publishProduct");
  put(m,"DataSpaceFlowController","product.unpublish","deleteProduct");
  put(m,"DataSpaceFlowController","data.preview","productSamples");
  put(m,"DataSpaceFlowController","data.search","searchProductContent");
  put(m,"DataSpaceFlowController","data.raw.download","productFile","productFileByQuery");
  put(m,"DataSpaceFlowController","application.submit","listIntents","createIntent");
  put(m,"DataSpaceFlowController","application.review","decideIntent");
  put(m,"DataSpaceFlowController","contract.read","listContracts","history");
  put(m,"DataSpaceFlowController","contract.edit","createContract","revise","contractOptions");
  put(m,"DataSpaceFlowController","contract.confirm","confirm");
  put(m,"DataSpaceFlowController","contract.revoke","revoke");
  put(m,"DataSpaceFlowController","delivery.execute","dispatchContract");
  put(m,"DataSpaceFlowController","delivery.receipt.read","listDeliveries","attempts","manifest","receipt");
  put(m,"DataSpaceFlowController","delivery.endpoint.manage","endpoints","endpoint");
  put(m,"DataSpaceFlowController","delivery.package.download","deliveryPackage");
  put(m,"DataSpaceQaController","data.catalog.read","datasets","access");
  put(m,"DataSpaceQaController","dataset.index.build","indexing");
  put(m,"DataSpaceQaController","data.preview","queryAssets","sourceFiles");
  put(m,"DataSpaceQaController","data.qa","askJson","askMultipart");
  put(m,"DataSpaceQaController","data.search","search","capabilities","retrieve","imageRetrieval");
  put(m,"DataSpaceQaController","data.raw.download","source");
  put(m,"QaEvidenceController","data.preview","read","render","describe","image");
  put(m,"DataSpaceQualityController","dataset.quality.run","request");
  put(m,"DataSpaceQualityController","dataset.quality.read","get","list");
  put(m,"ControlledViewExportController","data.view.export","list","export");
  put(m,"ControlledViewProposalController","dataset.share.manage","create","list","get","sources","files","targets");
  put(m,"ControlledViewProposalController","identity.self.read","withdraw");
  put(m,"ControlledViewPreviewController","dataset.share.manage","create","list","get","reconcile","receipt","file");
  put(m,"IdentityManagementController","identity.member.read","organizations","users","types");
  put(m,"IdentityManagementController","identity.member.create","create");
  put(m,"IdentityManagementController","identity.member.update","updateAccount","accountImpact");
  put(m,"IdentityManagementController","identity.member.type.assign","assign","typesImpact");
  put(m,"IdentityManagementController","identity.member.disable","state","logout");
  put(m,"IdentityManagementController","identity.member.reset","reset");
  put(m,"IdentityManagementController","identity.member.close","close");
  put(m,"IdentityManagementController","identity.platform-admin.manage","logoutNonAdmin","qualification","qualificationImpact");
  put(m,"IdentityManagementController","identity.type.manage","operations","createType","updateType","deleteType","typeImpact","deleteImpact");
  put(m,"IdentityManagementController","identity.self.read","changeReview","cancelReview");
  put(m,"RoleController","identity.member.read","get","listRoles","listRolePermissions","getRolePermissionTree");
  put(m,"RoleController","identity.type.manage","create","update","delete","setRolePermissions","impact","permissionsImpact","deleteImpact");
  MAPPING=Collections.unmodifiableMap(m);
 }
 private static void put(Map<String,String> map,String controller,String operation,String... methods) {
  if(!OperationCatalog.isKnown(operation))throw new IllegalArgumentException("Unimplemented operation");
  for(String method:methods)if(map.put(controller+"."+method,operation)!=null)throw new IllegalStateException("Duplicate endpoint operation");
 }
 public static String operation(String controller,String method,String verb) {
  if(controller.equals("MmIngestTaskController")&&method.equals("mapping")&&!Set.of("GET","HEAD").contains(verb))return "dataset.draft.manage";
  return MAPPING.get(controller+"."+method);
 }
 public static Map<String,String> all(){return MAPPING;}
}
