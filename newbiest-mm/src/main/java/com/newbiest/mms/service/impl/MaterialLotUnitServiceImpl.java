package com.newbiest.mms.service.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.newbiest.base.exception.ClientException;
import com.newbiest.base.exception.ClientParameterException;
import com.newbiest.base.exception.ExceptionManager;
import com.newbiest.base.model.NBHis;
import com.newbiest.base.service.BaseService;
import com.newbiest.base.utils.CollectionUtils;
import com.newbiest.base.utils.StringUtils;
import com.newbiest.base.utils.ThreadLocalContext;
import com.newbiest.commom.sm.model.StatusModel;
import com.newbiest.common.idgenerator.service.GeneratorService;
import com.newbiest.common.idgenerator.utils.GeneratorContext;
import com.newbiest.mms.SystemPropertyUtils;
import com.newbiest.mms.dto.MaterialLotAction;
import com.newbiest.mms.exception.MmsException;
import com.newbiest.mms.model.*;
import com.newbiest.mms.repository.*;
import com.newbiest.mms.service.MaterialLotUnitService;
import com.newbiest.mms.service.MmsService;
import com.newbiest.mms.service.PackageService;
import com.newbiest.mms.state.model.MaterialEvent;
import com.newbiest.mms.state.model.MaterialStatus;
import com.newbiest.mms.thread.ImportCobMLotThread;
import com.newbiest.mms.thread.ImportLcdMLotThread;
import com.newbiest.mms.thread.ImportMLotThread;
import com.newbiest.mms.thread.ImportMLotThreadResult;
import com.newbiest.mms.utils.CollectorsUtils;
import com.newbiest.msg.ResponseHeader;
import freemarker.template.utility.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Created by guoxunbo on 2020-01-17 12:39
 */
@Service
@Transactional
@Slf4j
public class MaterialLotUnitServiceImpl implements MaterialLotUnitService {

    private static final Integer DEFAULT_IMPORT_MLOT_POOL_SIZE = 30;

    @Autowired
    MmsService mmsService;

    @Autowired
    MaterialLotUnitRepository materialLotUnitRepository;

    @Autowired
    MaterialLotUnitHisRepository materialLotUnitHisRepository;

    @Autowired
    MaterialLotRepository materialLotRepository;

    @Autowired
    MaterialLotHistoryRepository materialLotHistoryRepository;

    @Autowired
    BaseService baseService;

    @Autowired
    GeneratorService generatorService;

    @Autowired
    PackageService packageService;

    @Autowired
    PackagedLotDetailRepository packagedLotDetailRepository;

    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        Integer importMLotPoolSize = SystemPropertyUtils.getImportMLotPoolSize();
        if (importMLotPoolSize == null) {
            log.warn("System property import mlot pool size is not set. so use default pool size");
            importMLotPoolSize = DEFAULT_IMPORT_MLOT_POOL_SIZE;
        }
        executorService = Executors.newFixedThreadPool(importMLotPoolSize);
    }

    public List<MaterialLotUnit> getUnitsByMaterialLotId(String materialLotId) throws ClientException{
        return materialLotUnitRepository.findByMaterialLotId(materialLotId);
    }

    /**
     * 创建之后只做接收动作
     * @return
     * @throws ClientException
     */
    public void receiveMLotWithUnit(MaterialLot materialLot, String warehouseName) throws ClientException {
        try {
            Warehouse warehouse = mmsService.getWarehouseByName(warehouseName);
            if (warehouse == null) {
                throw new ClientParameterException(MmsException.MM_WAREHOUSE_IS_NOT_EXIST, warehouseName);
            }
            Long warehouseRrn = warehouse.getObjectRrn();
            if(!StringUtils.isNullOrEmpty(materialLot.getReserved13())){
                warehouseRrn = Long.parseLong(materialLot.getReserved13());
            }

            MaterialLotAction materialLotAction = new MaterialLotAction();
            materialLotAction.setMaterialLotId(materialLot.getMaterialLotId());
            materialLotAction.setTargetWarehouseRrn(warehouseRrn);
            materialLotAction.setTransQty(materialLot.getCurrentQty());
            materialLotAction.setTransCount(materialLot.getCurrentSubQty());
            mmsService.stockIn(materialLot, materialLotAction);
            mmsService.stockInMaterialLotUnitAndSaveHis(materialLot);

            if(!StringUtils.isNullOrEmpty(materialLot.getParentMaterialLotId()) && MaterialLot.IMPORT_COB.equals(materialLot.getReserved7())){
                MaterialLot parentMaterialLot = materialLotRepository.findByMaterialLotIdAndOrgRrn(materialLot.getParentMaterialLotId(), ThreadLocalContext.getOrgRrn());
                if(MaterialStatus.STATUS_CREATE.equals(parentMaterialLot.getStatus())){
                    mmsService.changeMaterialLotState(parentMaterialLot, MaterialEvent.EVENT_BOX_RECEIVE, StringUtils.EMPTY);
                }
            }

        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 生成物料批次以及物料批次对应的单元
     * @param materialLotUnitList
     * @return
     * @throws ClientException
     */
    public List<MaterialLotUnit> createMLot(List<MaterialLotUnit> materialLotUnitList) throws ClientException {
        try {
            List<MaterialLotUnit> materialLotUnitArrayList = new ArrayList<>();
            Map<String, List<MaterialLotUnit>> materialUnitIdMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getUnitId));
            for(String unitId : materialUnitIdMap.keySet()){
                if(materialUnitIdMap.get(unitId).size() > 1){
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_UNIT_ID_REPEATS, unitId);
                }
            }
            Map<String, List<MaterialLotUnit>> materialLotMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit :: getLotId));
            for(String lotId : materialLotMap.keySet()){
                MaterialLot materialLotInfo = materialLotRepository.findByLotIdAndStatusCategoryNotIn(lotId, MaterialLot.STATUS_FIN);
                if(materialLotInfo != null){
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_IS_EXIST, lotId);
                }
            }

            //生成导入编码
            String importCode = "";
            if(StringUtils.isNullOrEmpty(materialLotUnitList.get(0).getReserved48())){
                importCode = generatorMLotUnitImportCode(MaterialLot.GENERATOR_INCOMING_MLOT_IMPORT_CODE_RULE);
            } else {
                importCode = materialLotUnitList.get(0).getReserved48();
            }
            Map<String, List<MaterialLotUnit>> materialUnitMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getMaterialName));
            List<Future<ImportMLotThreadResult>> importCallBackList = Lists.newArrayList();
            for (String materialName : materialUnitMap.keySet()) {
                Material material = validateAndGetMaterial(materialName);
                StatusModel statusModel = mmsService.getMaterialStatusModel(material);
                Map<String, List<MaterialLotUnit>> materialLotUnitMap = materialUnitMap.get(materialName).stream().collect(Collectors.groupingBy(MaterialLotUnit :: getLotId));
                for (String lotId : materialLotUnitMap.keySet()) {
                    List<MaterialLotUnit> materialLotUnits = materialLotUnitMap.get(lotId);

                    //验证晶圆是否存在Eng晶圆
                    String productType = MaterialLotUnit.PRODUCT_TYPE_PROD;
                    List<MaterialLotUnit> engUnitList = materialLotUnits.stream().filter(materialLotUnit -> MaterialLotUnit.PRODUCT_TYPE_ENG.equals(materialLotUnit.getProductType())).collect(Collectors.toList());
                    if(CollectionUtils.isNotEmpty(engUnitList)){
                        productType = MaterialLotUnit.PRODUCT_TYPE_ENG;
                    }
                    // 导入进行多线程处理 进行并行处理
                    ImportMLotThread importMLotThread = new ImportMLotThread();
                    importMLotThread.setMaterialLotRepository(materialLotRepository);
                    importMLotThread.setMmsService(mmsService);
                    importMLotThread.setBaseService(baseService);
                    importMLotThread.setMaterialLotUnitRepository(materialLotUnitRepository);
                    importMLotThread.setMaterialLotUnitHisRepository(materialLotUnitHisRepository);
                    importMLotThread.setSessionContext(ThreadLocalContext.getSessionContext());

                    String materialLotId = materialLotUnits.get(0).getMaterialLotId();
                    if (StringUtils.isNullOrEmpty(materialLotId)) {
                        materialLotId = mmsService.generatorMLotId(material);
                    }
                    importMLotThread.setMaterialLotId(materialLotId);
                    importMLotThread.setLotId(lotId);
                    importMLotThread.setImportCode(importCode);
                    importMLotThread.setMaterial(material);
                    importMLotThread.setStatusModel(statusModel);
                    importMLotThread.setMaterialLotUnits(materialLotUnits);
                    importMLotThread.setProductType(productType);

                    Future<ImportMLotThreadResult> importCallBack = executorService.submit(importMLotThread);
                    importCallBackList.add(importCallBack);
                }
            }

            // 最大等待返回次数 300*100最长30S
            int maxWaitCount = 300;
            String resultMessage = StringUtils.EMPTY;
            for (Future<ImportMLotThreadResult> importCallBack : importCallBackList) {
                if (!StringUtils.isNullOrEmpty(resultMessage) || maxWaitCount <= 0) {
                    log.warn("There has some import error. please see log get more details.");
                    break;
                }
                while (true) {
                    if (importCallBack.isDone()) {
                        ImportMLotThreadResult importResult = importCallBack.get();
                        if (ResponseHeader.RESULT_SUCCESS.equals(importResult.getResult())) {
                            materialLotUnitArrayList.addAll(importResult.getMaterialLotUnits());
                        } else {
                            resultMessage = importResult.getResultMessage();
                        }
                        break;
                    } else {
                        // 如果没做好，等待100ms,防止系统将CPU用光
                        Thread.sleep(100);
                        maxWaitCount--;
                        if (maxWaitCount == 0) {
                            resultMessage = MmsException.MM_MATERIAL_LOT_IMPORT_TIME_OUT;
                            break;
                        }
                    }
                }
            }
            List<MaterialLotUnit> mLotUnits = Lists.newArrayList();
            List<MaterialLotUnit> materialLotUnits = materialLotUnitRepository.findByReserved48(importCode);
            if(!StringUtils.isNullOrEmpty(resultMessage)){
                //停止线程
                for(Future<ImportMLotThreadResult> importCallBack : importCallBackList){
                    if(!importCallBack.isDone()){
                        importCallBack.cancel(true);
                    }
                }
                deleteImportMaterialLotUnit(importCode);
                return mLotUnits;
            } else if(CollectionUtils.isEmpty(materialLotUnits) || materialLotUnitList.size() != materialLotUnits.size()){
                deleteImportMaterialLotUnit(importCode);
                return mLotUnits;
            } else {
                return materialLotUnitArrayList;
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 导入出现异常，删除重新导入
     * 只有正常来料导入的才可以做删除操作，退料导入的不能做删除
     * @param importCode
     * @throws ClientException
     */
    private void deleteImportMaterialLotUnit(String importCode) throws ClientException{
        try {
            materialLotUnitRepository.deleteByImportCode(importCode);
            materialLotRepository.deleteByImportType(importCode);
            materialLotUnitHisRepository.deleteByImportCode(importCode);
            materialLotHistoryRepository.deleteByImportCode(importCode);
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 晶圆导入生成导入编码
     * @param ruleId
     * @return
     */
    private String generatorMLotUnitImportCode(String ruleId) throws ClientException{
        try {
            GeneratorContext generatorContext = new GeneratorContext();
            generatorContext.setRuleName(ruleId);
            String importCode = generatorService.generatorId(ThreadLocalContext.getOrgRrn(), generatorContext);
            return importCode;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * COB成品、COM原料导入  导入自动装箱
     * 一个Lot一箱
     * @param materialLotUnitList
     * @return
     * @throws ClientException
     */
    public List<MaterialLotUnit> createCobMLot(List<MaterialLotUnit> materialLotUnitList) throws ClientException {
        try {
            List<MaterialLotUnit> materialLotUnitArrayList = new ArrayList<>();
            Map<String, List<MaterialLotUnit>> materialUnitIdMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getUnitId));
            for(String unitId : materialUnitIdMap.keySet()){
                if(materialUnitIdMap.get(unitId).size() > 1){
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_UNIT_ID_REPEATS, unitId);
                }
            }
            //验证箱号是否存在
            Map<String, List<MaterialLotUnit>> boxIdMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit :: getLotId));
            for(String parentMLotId : boxIdMap.keySet()){
                MaterialLot materialLot = materialLotRepository.findByMaterialLotIdAndOrgRrn(parentMLotId, ThreadLocalContext.getOrgRrn());
                if(materialLot != null){
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_IS_EXIST, parentMLotId);
                }
                //验证晶圆入库备注信息是否一致
                List<MaterialLotUnit> materialLotUnits = boxIdMap.get(parentMLotId);
                Set treasuryNoteSet = materialLotUnits.stream().map(materialLotUnit -> materialLotUnit.getTreasuryNote()).collect(Collectors.toSet());
                if (treasuryNoteSet != null &&  treasuryNoteSet.size() > 1) {
                    throw new ClientParameterException(MmsException.MATERIALLOT_UNIT_TREASURY_NOTE_IS_NOT_SAME, parentMLotId);
                }
            }
            //验证LotId是否已经存在
            Map<String, List<MaterialLotUnit>> materialLotMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit :: getLotId));
            for(String lotId : materialLotMap.keySet()){
                MaterialLot mLot = materialLotRepository.findByMaterialLotIdAndOrgRrn(lotId, ThreadLocalContext.getOrgRrn());
                if(mLot != null){
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_IS_EXIST, lotId);
                }
            }
            String importCode = generatorMLotUnitImportCode(MaterialLot.GENERATOR_INCOMING_MLOT_IMPORT_CODE_RULE);

            Map<String, List<MaterialLotUnit>> materialUnitMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getMaterialName));
            List<Future<ImportMLotThreadResult>> cobImportCallBackList = Lists.newArrayList();
            List<String> bboxIdList = Lists.newArrayList();
            for (String materialName : materialUnitMap.keySet()) {
                Material material = validateAndGetMaterial(materialName);
                StatusModel statusModel = mmsService.getMaterialStatusModel(material);
                Map<String, List<MaterialLotUnit>> mlotDurableMap = materialUnitMap.get(materialName).stream().collect(Collectors.groupingBy(MaterialLotUnit :: getDurable));
                for (String durable : mlotDurableMap.keySet()) {
                    List<MaterialLotUnit> materialLotUnits = mlotDurableMap.get(durable);
                    String parentMaterialLotId = materialLotUnits.get(0).getMaterialLotId();
                    bboxIdList.add(parentMaterialLotId);
                    ImportCobMLotThread importCobMLotThread = new ImportCobMLotThread();
                    importCobMLotThread.setMaterialLotRepository(materialLotRepository);
                    importCobMLotThread.setMmsService(mmsService);
                    importCobMLotThread.setBaseService(baseService);
                    importCobMLotThread.setPackageService(packageService);
                    importCobMLotThread.setMaterialLotUnitRepository(materialLotUnitRepository);
                    importCobMLotThread.setMaterialLotUnitHisRepository(materialLotUnitHisRepository);
                    importCobMLotThread.setSessionContext(ThreadLocalContext.getSessionContext());
                    importCobMLotThread.setMaterialLotId(durable);
                    importCobMLotThread.setLotId(durable);
                    importCobMLotThread.setParentMaterialLotId(parentMaterialLotId);
                    importCobMLotThread.setImportCode(importCode);
                    importCobMLotThread.setMaterial(material);
                    importCobMLotThread.setStatusModel(statusModel);
                    importCobMLotThread.setMaterialLotUnitList(materialLotUnits);
                    Future<ImportMLotThreadResult> importCallBack = executorService.submit(importCobMLotThread);
                    cobImportCallBackList.add(importCallBack);
                }
            }

            int maxWaitQty = 300;
            String resultMessage = StringUtils.EMPTY;
            for (Future<ImportMLotThreadResult> cobImportCallBack : cobImportCallBackList) {
                if (!StringUtils.isNullOrEmpty(resultMessage) || maxWaitQty <= 0) {
                    log.warn("Import error. please see log get more details.");
                    break;
                }
                while (true) {
                    if (cobImportCallBack.isDone()) {
                        ImportMLotThreadResult cobImportResult = cobImportCallBack.get();
                        if (ResponseHeader.RESULT_SUCCESS.equals(cobImportResult.getResult())) {
                            materialLotUnitArrayList.addAll(cobImportResult.getMaterialLotUnits());
                        } else {
                            resultMessage = cobImportResult.getResultMessage();
                        }
                        break;
                    } else {
                        Thread.sleep(100);
                        maxWaitQty--;
                        if (maxWaitQty == 0) {
                            resultMessage = MmsException.MM_MATERIAL_LOT_IMPORT_TIME_OUT;
                            break;
                        }
                    }
                }
            }
            List<MaterialLotUnit> mLotUnits = Lists.newArrayList();
            List<MaterialLotUnit> materialLotUnits = materialLotUnitRepository.findByReserved48(importCode);
            if(!StringUtils.isNullOrEmpty(resultMessage)){
                for(Future<ImportMLotThreadResult> importCallBack : cobImportCallBackList){
                    if(!importCallBack.isDone()){
                        importCallBack.cancel(true);
                    }
                }
                deleteImportMaterialLotUnit(importCode);
                if(CollectionUtils.isNotEmpty(bboxIdList)){
                    packagedLotDetailRepository.deleteByPackagedLotIdIn(bboxIdList);
                }
                return mLotUnits;
            } else if(CollectionUtils.isEmpty(materialLotUnits) || materialLotUnitList.size() != materialLotUnits.size()){
                deleteImportMaterialLotUnit(importCode);
                if(CollectionUtils.isNotEmpty(bboxIdList)){
                    packagedLotDetailRepository.deleteByPackagedLotIdIn(bboxIdList);
                }
                return mLotUnits;
            } else {
                return materialLotUnitArrayList;
            }
        } catch (Exception e){
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 验证并且获取
     * @param materialName
     * @return
     * @throws ClientException
     */
    private Material validateAndGetMaterial(String materialName) throws ClientException{
        try {
            Material material = mmsService.getRawMaterialByName(materialName);
            if (material == null) {
                RawMaterial rawMaterial = new RawMaterial();
                rawMaterial.setName(materialName);
                material = mmsService.createRawMaterial(rawMaterial);
            }
            return material;
        }catch (Exception e){
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * MES晶圆退仓库时验证晶圆信息是否已经存在，如果存在则修改状态
     * 晶圆换箱号则修改原箱号中的晶圆状态
     * @param materialLotUnitList
     */
    public void validateAndCreateMLotUnit(List<MaterialLotUnit> materialLotUnitList) throws ClientException{
        try {
            Warehouse warehouse;
            List<MaterialLotUnit> materialLotUnits = Lists.newArrayList();
            Map<String, List<MaterialLotUnit>> materialLotUnitMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getMaterialLotId));
            for(String materialLotId : materialLotUnitMap.keySet()){
                MaterialLot materialLot = mmsService.getMLotByMLotId(materialLotId);
                List<MaterialLotUnit> materialLotUnitInfo = materialLotUnitMap.get(materialLotId);

                for(MaterialLotUnit materialLotUnit : materialLotUnitInfo){
                    String warehouseName = materialLotUnit.getReserved13();
                    if(!StringUtils.isNullOrEmpty(warehouseName)){
                        warehouse = mmsService.getWarehouseByName(warehouseName);
                        materialLotUnit.setReserved13(warehouse.getObjectRrn().toString());
                    }
                }
                if(materialLot != null){
                    //如果是已经装箱批次则，复活箱号和批次信息
                    if(!StringUtils.isNullOrEmpty(materialLot.getPackageType())){
                        materialLot.setCurrentQty(materialLot.getReceiveQty());
                        materialLot.setStatusCategory(MaterialLotUnit.STATE_CREATE);
                        materialLot.setStatus(MaterialLotUnit.STATE_CREATE);
                        materialLot.setPreStatus("");
                        materialLot.setPreStatusCategory("");
                        materialLotRepository.saveAndFlush(materialLot);

                        String durable = materialLotUnitInfo.get(0).getDurable();
                        MaterialLot mLotCst = materialLotRepository.findByMaterialLotIdAndOrgRrn(durable, ThreadLocalContext.getOrgRrn());
                        if(mLotCst != null){
                            materialLot.setCurrentQty(materialLot.getReceiveQty());
                            materialLot.setStatusCategory(MaterialLot.STATUS_FIN);
                            materialLot.setStatus(MaterialLot.CATEGORY_PACKAGE);
                            materialLot.setPreStatus(MaterialStatus.STATUS_STOCK);
                            materialLot.setPreStatusCategory(MaterialStatus.STATUS_IN);
                            materialLotRepository.saveAndFlush(materialLot);

                            MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, MaterialLotHistory.TRANS_TYPE_MES_RETURN_MATERIAL);
                            materialLotHistoryRepository.save(history);
                        }
                        for(MaterialLotUnit materialLotUnit : materialLotUnitInfo){
                            materialLotUnitRepository.updateMLotUnitByUnitIdAndMLotId(materialLotUnit.getUnitId(), materialLotUnit.getDurable(), MaterialLotUnit.STATE_CREATE);
                        }
                    } else {
                        //删除并重新创建并自动装箱
                        for(MaterialLotUnit materialLotUnit : materialLotUnitInfo){
                            MaterialLotUnit oldMLotUnit = materialLotUnitRepository.findByMaterialLotIdAndUnitId(materialLotUnit.getMaterialLotId(), materialLotUnit.getUnitId());
                            if(oldMLotUnit != null){
                                materialLotUnitRepository.delete(oldMLotUnit);

                                MaterialLotUnitHistory materialLotUnitHistory = (MaterialLotUnitHistory) baseService.buildHistoryBean(materialLotUnit, MaterialLotHistory.TRANS_TYPE_MES_RETURN_DELETE);
                                materialLotUnitHisRepository.save(materialLotUnitHistory);
                            } else {
                                materialLotUnitRepository.updateMLotUnitByUnitIdAndMLotId(materialLotUnit.getUnitId(), materialLotUnit.getMaterialLotId(), MaterialLotUnit.STATE_CREATE);
                            }
                        }
                        materialLotRepository.delete(materialLot);
                        MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, MaterialLotHistory.TRANS_TYPE_MES_RETURN_DELETE);
                        materialLotHistoryRepository.save(history);

                        materialLotUnits.addAll(materialLotUnitInfo);
                    }
                } else{
                    //修改unit表中存在且已发料的晶圆状态
                    for(MaterialLotUnit materialLotUnit : materialLotUnitInfo){
                        List<MaterialLotUnit> issuedMLotUnitInfo = materialLotUnitRepository.findByUnitIdAndState(materialLotUnit.getUnitId(), MaterialLotUnit.STATE_ISSUE);
                        for(MaterialLotUnit issuedMLotUnit : issuedMLotUnitInfo){
                            materialLotUnitRepository.delete(issuedMLotUnit);

                            MaterialLotUnitHistory materialLotUnitHistory = (MaterialLotUnitHistory) baseService.buildHistoryBean(materialLotUnit, MaterialLotHistory.TRANS_TYPE_MES_RETURN_DELETE);
                            materialLotUnitHisRepository.save(materialLotUnitHistory);
                        }
                    }
                    materialLotUnits.addAll(materialLotUnitInfo);
                }
            }
            //重新导入退仓库的晶圆
            if(CollectionUtils.isNotEmpty(materialLotUnits)){
                String importCode = generatorMLotUnitImportCode(MaterialLot.GENERATOR_INCOMING_MLOT_IMPORT_CODE_RULE);
                Map<String, List<MaterialLotUnit>> materialUnitMap = materialLotUnits.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getMaterialName));
                for (String materialName : materialUnitMap.keySet()) {
                    Material material = validateAndGetMaterial(materialName);
                    StatusModel statusModel = mmsService.getMaterialStatusModel(material);
                    Map<String, List<MaterialLotUnit>> mlotDurableMap = materialUnitMap.get(materialName).stream().collect(Collectors.groupingBy(MaterialLotUnit :: getDurable));
                    for (String durable : mlotDurableMap.keySet()) {
                        List<MaterialLotUnit> mLotUnits = mlotDurableMap.get(durable);
                        String parentMaterialLotId = mLotUnits.get(0).getMaterialLotId();
                        BigDecimal totalQty = mLotUnits.stream().collect(CollectorsUtils.summingBigDecimal(MaterialLotUnit :: getCurrentQty));
                        BigDecimal currentSubQty = new BigDecimal(mLotUnits.size());
                        String location = mLotUnits.get(0).getReserved4();
                        Map<String, Object> propsMap = Maps.newHashMap();
                        propsMap.put("category", MaterialLot.CATEGORY_UNIT);
                        propsMap.put("durable", durable.toUpperCase());
                        propsMap.put("supplier", mLotUnits.get(0).getSupplier());
                        propsMap.put("shipper", mLotUnits.get(0).getShipper());
                        propsMap.put("grade", mLotUnits.get(0).getGrade());
                        propsMap.put("lotId", durable.toUpperCase());
                        propsMap.put("reserved1",mLotUnits.get(0).getReserved1());
                        propsMap.put("reserved4",mLotUnits.get(0).getTreasuryNote());
                        propsMap.put("reserved6",location);
                        propsMap.put("reserved7",mLotUnits.get(0).getReserved7());
                        if(MaterialLot.BONDED_PROPERTY_ZSH.equals(location)){
                            propsMap.put("reserved14", MaterialLotInventory.ZSH_DEFAULT_STORAGE_ID);
                        } else if(MaterialLot.LOCATION_SH.equals(location)){
                            propsMap.put("reserved14", MaterialLotInventory.SH_DEFAULT_STORAGE_ID);
                        }  else {
                            propsMap.put("reserved14", materialLotUnitList.get(0).getReserved14());
                        }
                        propsMap.put("reserved13", mLotUnits.get(0).getReserved13());
                        propsMap.put("reserved22", mLotUnits.get(0).getReserved22());
                        propsMap.put("reserved23", mLotUnits.get(0).getReserved23());
                        propsMap.put("reserved24", mLotUnits.get(0).getReserved24());
                        propsMap.put("reserved25", mLotUnits.get(0).getReserved25());
                        propsMap.put("reserved26", mLotUnits.get(0).getReserved26());
                        propsMap.put("reserved27", mLotUnits.get(0).getReserved27());
                        propsMap.put("reserved28", mLotUnits.get(0).getReserved28());
                        propsMap.put("reserved29", mLotUnits.get(0).getReserved29());
                        propsMap.put("reserved32", mLotUnits.get(0).getReserved32());
                        propsMap.put("reserved33", mLotUnits.get(0).getReserved33());
                        propsMap.put("reserved34", mLotUnits.get(0).getReserved34());
                        propsMap.put("reserved35", mLotUnits.get(0).getReserved35());
                        propsMap.put("reserved36", mLotUnits.get(0).getReserved36());
                        propsMap.put("reserved37", mLotUnits.get(0).getReserved37());
                        propsMap.put("reserved38", mLotUnits.get(0).getReserved38());
                        propsMap.put("reserved39", mLotUnits.get(0).getReserved39());
                        propsMap.put("reserved40", mLotUnits.get(0).getReserved40());
                        propsMap.put("reserved41", mLotUnits.get(0).getReserved41());
                        propsMap.put("reserved45", mLotUnits.get(0).getReserved45());
                        propsMap.put("reserved46", mLotUnits.get(0).getReserved46());
                        propsMap.put("reserved47", mLotUnits.get(0).getReserved47());
                        propsMap.put("reserved49", mLotUnits.get(0).getReserved49());
                        propsMap.put("reserved50", mLotUnits.get(0).getReserved50());
                        propsMap.put("reserved48", importCode);

                        MaterialLotAction materialLotAction = new MaterialLotAction(durable, StringUtils.EMPTY, propsMap, totalQty, currentSubQty, StringUtils.EMPTY);
                        MaterialLot materialLot = mmsService.createMLot(material, statusModel, materialLotAction);

                        List<MaterialLotAction> materialLotActions = Lists.newArrayList();
                        MaterialLotAction mLotAction = new MaterialLotAction();
                        mLotAction.setMaterialLotId(materialLot.getMaterialLotId());
                        mLotAction.setTransQty(materialLot.getCurrentQty());
                        mLotAction.setCobImportPack("1");
                        materialLotActions.add(mLotAction);
                        packageService.packageMLots(materialLotActions, parentMaterialLotId, "COBPackCase");

                        for (MaterialLotUnit mLotUnit : mLotUnits) {
                            mLotUnit.setDurable(durable);
                            mLotUnit.setLotId(parentMaterialLotId);
                            mLotUnit.setMaterialLotId(materialLot.getMaterialLotId());
                            mLotUnit.setUnitId(mLotUnit.getUnitId().toUpperCase());
                            mLotUnit.setMaterialLotRrn(materialLot.getObjectRrn());
                            mLotUnit.setReceiveDate(materialLot.getReceiveDate());
                            mLotUnit.setReserved1(materialLot.getReserved1());
                            mLotUnit.setReceiveQty(mLotUnit.getCurrentQty());
                            mLotUnit.setCurrentSubQty(BigDecimal.ONE);
                            mLotUnit.setReserved14(materialLot.getReserved14());
                            mLotUnit.setReserved18("0");
                            mLotUnit.setReserved25(materialLot.getReserved25());
                            mLotUnit.setReserved6(StringUtils.EMPTY);
                            mLotUnit.setReserved7(StringUtils.EMPTY);
                            mLotUnit.setReserved48(importCode);
                            mLotUnit.setMaterial(material);
                            mLotUnit = materialLotUnitRepository.saveAndFlush(mLotUnit);

                            MaterialLotUnitHistory history = (MaterialLotUnitHistory) baseService.buildHistoryBean(mLotUnit, NBHis.TRANS_TYPE_CREATE);
                            materialLotUnitHisRepository.save(history);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * WLT导入更具FabLotId和第一片waferId获取载具号
     * @param materialLotUnitList
     * @return
     * @throws ClientException
     */
    public List<MaterialLotUnit> getMaterialLotUnitByFabLotAndWaferId(List<MaterialLotUnit> materialLotUnitList, String importType) throws ClientException {
        try {
            List<String> cpImportList = Lists.newArrayList(MaterialLotUnit.FAB_SENSOR, MaterialLotUnit.FAB_SENSOR_2UNMEASURED, MaterialLotUnit.SENSOR_CP_KLT,
                    MaterialLotUnit.SENSOR_CP, MaterialLotUnit.SENSOR_UNMEASURED, MaterialLotUnit.LCD_CP_25UNMEASURED, MaterialLotUnit.LCD_CP);
            List<MaterialLotUnit> materialLotUnits = Lists.newArrayList();
            Map<String, List<MaterialLotUnit>> materialLotUnitMap = Maps.newHashMap();

            if(importType.equals(MaterialLotUnit.WLA_UNMEASURED)){
                materialLotUnitMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getDurable));
            } else {
                materialLotUnitMap = materialLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getReserved30));
            }
            for(String fabLotId : materialLotUnitMap.keySet()){
                List<MaterialLotUnit> mLotUnitList = materialLotUnitMap.get(fabLotId);
                if (mLotUnitList.size() > MaterialLotUnit.THIRTEEN && importType.equals(MaterialLotUnit.WLA_UNMEASURED)){
                    throw new ClientParameterException(MmsException.MM_WLA_IMPORT_MATERIAL_LOT_UNIT_SIZE_IS_OVER_THIRTEEN, fabLotId);
                }
                //CP的晶圆同一个FabLotId的需要按照CartonNo进行分组，构建多个Lot
                if(cpImportList.contains(importType)){
                    Map<String, List<MaterialLotUnit>> cartonNoMap = mLotUnitList.stream().collect(Collectors.groupingBy(MaterialLotUnit :: getReserved39));
                    for(String cartonNo : cartonNoMap.keySet()){
                        List<MaterialLotUnit> cartonMLotUnitList = cartonNoMap.get(cartonNo);
                        cartonMLotUnitList = getImportMaterialLotUnitByFabLotIdAndMinWaferId(cartonMLotUnitList, fabLotId);
                        materialLotUnits.addAll(cartonMLotUnitList);
                    }
                } else {
                    mLotUnitList = getImportMaterialLotUnitByFabLotIdAndMinWaferId(mLotUnitList, fabLotId);
                    materialLotUnits.addAll(mLotUnitList);
                }
            }
            return materialLotUnits;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 来了导入按照FabLotId分组，取最小的waferId与FablotId做拼接为LotId
     * 如果是CP的批次，则再按照CartonNo分组，同一个FabLotId的批次分成多个Lot
     * @param mLotUnitList
     * @return
     * @throws ClientException
     */
    private List<MaterialLotUnit> getImportMaterialLotUnitByFabLotIdAndMinWaferId(List<MaterialLotUnit> mLotUnitList, String fabLotId) throws ClientException{
        try {
            Integer minWaferId = 0;
            for (MaterialLotUnit materialLotUnit : mLotUnitList) {
                if(minWaferId == 0 || minWaferId > Integer.parseInt(materialLotUnit.getReserved31())){
                    minWaferId = Integer.parseInt(materialLotUnit.getReserved31());
                }
            }
            String waferId = minWaferId+"";
            waferId = StringUtil.leftPad(waferId , 2 , "0");
            String lotId = fabLotId.split("\\.")[0] +"."+ waferId;
            for(MaterialLotUnit materialLotUnit : mLotUnitList){
                materialLotUnit.setLotId(lotId);
                materialLotUnit.setReserved30(fabLotId.split("\\.")[0]);
            }
            return mLotUnitList;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 获取出货标注的物料批次的晶圆信息
     * @param materialLotActions
     * @return materialLotUnitList
     * @throws ClientException
     */
    public List<MaterialLotUnit> queryStockOutTagMLotUnits(List<MaterialLotAction> materialLotActions) throws ClientException{
        try{
            List<MaterialLotUnit> materialLotUnitList = Lists.newArrayList();
            List<MaterialLot> materialLots = materialLotActions.stream().map(materialLotAction -> mmsService.getMLotByMLotId(materialLotAction.getMaterialLotId(), true)).collect(Collectors.toList());
            for(MaterialLot materialLot : materialLots){
                List<MaterialLotUnit> materialLotUnits = materialLotUnitRepository.findByMaterialLotId(materialLot.getMaterialLotId());
                materialLotUnitList.addAll(materialLotUnits);
            }
            return materialLotUnitList;
        } catch (Exception e){
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 创建FT的物料批次
     * @param materialLotUnits
     * @return
     * @throws ClientException
     */
    public List<MaterialLotUnit> createFTMLot(List<MaterialLotUnit> materialLotUnits) throws ClientException{
        try {
            String importCode = generatorMLotUnitImportCode(MaterialLot.GENERATOR_INCOMING_MLOT_IMPORT_CODE_RULE);
            Map<String, List<MaterialLotUnit>> materialUnitMap = materialLotUnits.stream().collect(Collectors.groupingBy(MaterialLotUnit:: getMaterialName));
            for(String materialName : materialUnitMap.keySet()){
                Material material = mmsService.getRawMaterialByName(materialName);
                StatusModel statusModel = mmsService.getMaterialStatusModel(material);
                List<MaterialLotUnit> materialLotUnitList = materialUnitMap.get(materialName);
                for(MaterialLotUnit materialLotUnit : materialLotUnitList){
                    Map<String, Object> propsMap = Maps.newHashMap();
                    propsMap.put("category", MaterialLot.CATEGORY_UNIT);
                    if(!StringUtils.isNullOrEmpty(materialLotUnit.getDurable())){
                        propsMap.put("durable", materialLotUnit.getDurable().toUpperCase());
                    }
                    if(MaterialLotUnit.PRODUCT_TYPE_ENG.equals(materialLotUnit.getProductType())){
                        propsMap.put("productType", MaterialLotUnit.PRODUCT_TYPE_ENG);
                    }
                    //FT导入的产品二级代码为三位的转换为四位
                    String subCode = materialLotUnit.getReserved1();
                    if(!StringUtils.isNullOrEmpty(subCode) && subCode.length() == 3){
                        subCode = subCode + materialLotUnit.getUnitId().substring(0,1);
                    }
                    //WLT 将二级代码增加一位（第五位）
                    if(MaterialLot.IMPORT_WLT.equals(materialLotUnit.getReserved49()) && !StringUtils.isNullOrEmpty(materialLotUnit.getSubCode5()) && subCode.length() == 4){
                        subCode = subCode + materialLotUnit.getSubCode5();
                    }else if(MaterialLot.IMPORT_SENSOR.equals(materialLotUnit.getReserved49()) && !StringUtils.isNullOrEmpty(materialLotUnit.getSubCode5())){
                        //sensor封装回货(-3未测) 将二级代码增加一位（第五位）
                        subCode = subCode + materialLotUnit.getSubCode5();
                    }
                    propsMap.put("supplier", materialLotUnit.getSupplier());
                    propsMap.put("shipper", materialLotUnit.getShipper());
                    propsMap.put("grade", materialLotUnit.getGrade());
                    propsMap.put("lotId", materialLotUnit.getUnitId().toUpperCase());
                    propsMap.put("lotCst", materialLotUnit.getUnitId().toUpperCase().split("-")[0]);
                    propsMap.put("sourceProductId", materialLotUnit.getSourceProductId());
                    propsMap.put("engineerName", materialLotUnit.getEngineerName());

                    propsMap.put("reserved1",subCode);
                    propsMap.put("reserved4",materialLotUnit.getTreasuryNote());
                    propsMap.put("reserved6",materialLotUnit.getReserved4());
                    propsMap.put("reserved7",materialLotUnit.getReserved7());
                    propsMap.put("reserved13",materialLotUnit.getReserved13());
                    if(MaterialLot.LOCATION_SH.equals(materialLotUnit.getReserved14())){
                        propsMap.put("reserved14", MaterialLotInventory.SH_DEFAULT_STORAGE_ID);
                    } else if(MaterialLot.BONDED_PROPERTY_ZSH.equals(materialLotUnit.getReserved14())){
                        propsMap.put("reserved14",MaterialLotInventory.ZSH_DEFAULT_STORAGE_ID);
                    } else {
                        propsMap.put("reserved14",materialLotUnit.getReserved14());
                    }
                    propsMap.put("reserved22",materialLotUnit.getReserved22());
                    propsMap.put("reserved23",materialLotUnit.getReserved23());
                    propsMap.put("reserved24",materialLotUnit.getReserved24());
                    propsMap.put("reserved25",materialLotUnit.getReserved25());
                    propsMap.put("reserved26",materialLotUnit.getReserved26());
                    propsMap.put("reserved27",materialLotUnit.getReserved27());
                    propsMap.put("reserved28",materialLotUnit.getReserved28());
                    propsMap.put("reserved29",materialLotUnit.getReserved29());
                    propsMap.put("reserved32",materialLotUnit.getReserved32());
                    propsMap.put("reserved33",materialLotUnit.getReserved33());
                    propsMap.put("reserved34",materialLotUnit.getReserved34());
                    propsMap.put("reserved35",materialLotUnit.getReserved35());
                    propsMap.put("reserved36",materialLotUnit.getReserved36());
                    propsMap.put("reserved37",materialLotUnit.getReserved37());
                    propsMap.put("reserved38",materialLotUnit.getReserved38());
                    propsMap.put("reserved39",materialLotUnit.getReserved39());
                    propsMap.put("reserved40",materialLotUnit.getReserved40());
                    propsMap.put("reserved41",materialLotUnit.getReserved41());
                    propsMap.put("reserved45",materialLotUnit.getReserved45());
                    propsMap.put("reserved46",materialLotUnit.getReserved46());
                    propsMap.put("reserved47",materialLotUnit.getReserved47());
                    propsMap.put("reserved49",materialLotUnit.getReserved49());
                    propsMap.put("reserved50",materialLotUnit.getReserved50());
                    propsMap.put("reserved48",importCode);

                    MaterialLotAction materialLotAction = new MaterialLotAction(materialLotUnit.getUnitId().toUpperCase(), StringUtils.EMPTY, propsMap, materialLotUnit.getCurrentQty(), BigDecimal.ONE, StringUtils.EMPTY);
                    MaterialLot materialLot = mmsService.createMLot(material, statusModel, materialLotAction);

                    if(!StringUtils.isNullOrEmpty(materialLotUnit.getDurable())){
                        materialLotUnit.setDurable(materialLotUnit.getDurable().toUpperCase());
                    }
                    materialLotUnit.setReserved7(StringUtils.EMPTY);
                    materialLotUnit.setReserved1(subCode);
                    materialLotUnit.setLotId(materialLotUnit.getLotId().toUpperCase());
                    materialLotUnit.setUnitId(materialLotUnit.getUnitId().toUpperCase());//晶圆号小写转大写
                    materialLotUnit.setLotCst(materialLot.getLotCst().toUpperCase());//晶圆号小写转大写
                    materialLotUnit.setMaterialLotRrn(materialLot.getObjectRrn());
                    materialLotUnit.setMaterialLotId(materialLot.getMaterialLotId());
                    materialLotUnit.setLotId(materialLot.getLotId());
                    materialLotUnit.setReceiveQty(materialLotUnit.getCurrentQty());
                    materialLotUnit.setReserved14(materialLot.getReserved14());
                    materialLotUnit.setReserved48(importCode);
                    materialLotUnit.setMaterial(material);
                    materialLotUnit.setReceiveDate(materialLot.getReceiveDate());
                    materialLotUnit = materialLotUnitRepository.saveAndFlush(materialLotUnit);

                    MaterialLotUnitHistory history = (MaterialLotUnitHistory) baseService.buildHistoryBean(materialLotUnit, NBHis.TRANS_TYPE_CREATE);
                    history.setTransQty(materialLotUnit.getReceiveQty());
                    materialLotUnitHisRepository.save(history);
                }
            }
            return materialLotUnits;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }


    /**
     * LCD成品导入
     * @param materialLotList
     * @return
     * @throws ClientException
     */
    @Override
    public String importLcdFinishMLot(List<MaterialLot> materialLotList) throws ClientException {
        try {
            for(MaterialLot materialLot : materialLotList){
                MaterialLot oldMaterialLot = mmsService.getMLotByMLotId(materialLot.getMaterialLotId());
                if (oldMaterialLot != null) {
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_IS_EXIST, materialLot.getMaterialLotId());
                }
            }
            List<String> parentLotIdList = Lists.newArrayList();
            Map<String, List<MaterialLot>> packedMLotMap = materialLotList.stream().collect(Collectors.groupingBy(MaterialLot:: getParentMaterialLotId));
            for(String parentMaterialLotId : packedMLotMap.keySet()){
                MaterialLot oldMLot = mmsService.getMLotByMLotId(parentMaterialLotId);
                if (oldMLot != null) {
                    throw new ClientParameterException(MmsException.MM_MATERIAL_LOT_IS_EXIST, parentMaterialLotId);
                }
                List<MaterialLot> materialLots = packedMLotMap.get(parentMaterialLotId);
                MaterialLotPackageType materialLotPackageType = packageService.getMaterialPackageTypeByName(MaterialLot.LCD_PACKCASE);
                packageService.validationPackageRule(materialLots, materialLotPackageType);
                parentLotIdList.add(parentMaterialLotId);
            }
            String importCode = generatorMLotUnitImportCode(MaterialLot.GENERATOR_INCOMING_MLOT_IMPORT_CODE_RULE);
            Map<String, List<MaterialLot>> materialNameMap = materialLotList.stream().collect(Collectors.groupingBy(MaterialLot :: getMaterialName));
            List<Future<ImportMLotThreadResult>> importCallBackList = Lists.newArrayList();
            for(String materialName : materialNameMap.keySet()){
                Material material = mmsService.getProductByName(materialName);
                if (material == null) {
                    material = mmsService.saveProductAndSetStatusModelRrn(materialName);
                }
                List<MaterialLot> materialLots = materialNameMap.get(materialName);
                Map<String, List<MaterialLot>> packedLotMap = materialLots.stream().collect(Collectors.groupingBy(MaterialLot:: getParentMaterialLotId));
                for(String parentMaterialLotId : packedLotMap.keySet()){
                    List<MaterialLot> packedLotDetialList = packedLotMap.get(parentMaterialLotId);
                    Integer totalQty = packedLotDetialList.stream().collect(Collectors.summingInt(materialLot -> materialLot.getCurrentQty().intValue()));
                    //先创建父批次
                    MaterialLot packedMaterialLot = (MaterialLot) packedLotDetialList.get(0).clone();
                    packedMaterialLot.setMaterial(material);
                    packedMaterialLot.setReserved2("N");
                    packedMaterialLot.setParentMaterialLotId(null);
                    packedMaterialLot.setStatusModelRrn(material.getStatusModelRrn());
                    packedMaterialLot.setReserved7(MaterialLotUnit.PRODUCT_CLASSIFY_COG);
                    packedMaterialLot.setMaterialLotId(parentMaterialLotId);
                    packedMaterialLot.setCurrentQty(new BigDecimal(totalQty));
                    packedMaterialLot.initialMaterialLot();
                    if(MaterialLot.LOCATION_SH.equals(packedMaterialLot.getReserved6())){
                        packedMaterialLot.setReserved14(MaterialLotInventory.SH_DEFAULT_STORAGE_ID);
                    } else if(MaterialLot.BONDED_PROPERTY_ZSH.equals(packedMaterialLot.getReserved6())){
                        packedMaterialLot.setReserved14(MaterialLotInventory.ZSH_DEFAULT_STORAGE_ID);
                    }
                    packedMaterialLot.setPackageType(MaterialLot.LCD_PACKCASE);
                    packedMaterialLot.setMaterialType(material.getMaterialType());
                    packedMaterialLot.setStatusCategory(MaterialStatus.STATUS_CREATE);
                    packedMaterialLot.setStatus(MaterialStatus.STATUS_CREATE);
                    packedMaterialLot.setReserved48(importCode);
                    packedMaterialLot.setReserved49(MaterialLot.IMPORT_COG);
                    packedMaterialLot.setReserved50("17");
                    packedMaterialLot = materialLotRepository.saveAndFlush(packedMaterialLot);

                    MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(packedMaterialLot, MaterialLotHistory.TRANS_TYPE_CREATE_PACKAGE);
                    materialLotHistoryRepository.save(history);

                    List<List<MaterialLot>> mLotGroupList = getMaterialLotGroupList(packedLotDetialList, 20);
                    for(List<MaterialLot> mLotList : mLotGroupList){
                        ImportLcdMLotThread importLcdMLotThread = new ImportLcdMLotThread();
                        importLcdMLotThread.setMaterialLotRepository(materialLotRepository);
                        importLcdMLotThread.setMaterialLotHistoryRepository(materialLotHistoryRepository);
                        importLcdMLotThread.setBaseService(baseService);
                        importLcdMLotThread.setPackageService(packageService);
                        importLcdMLotThread.setPackagedLotDetailRepository(packagedLotDetailRepository);
                        importLcdMLotThread.setSessionContext(ThreadLocalContext.getSessionContext());

                        importLcdMLotThread.setTotalQty(totalQty);
                        importLcdMLotThread.setMaterialLotList(mLotList);
                        importLcdMLotThread.setImportCode(importCode);
                        importLcdMLotThread.setParentMaterialLotId(parentMaterialLotId);
                        importLcdMLotThread.setPackedMaterialLot(packedMaterialLot);
                        importLcdMLotThread.setMaterial(material);

                        Future<ImportMLotThreadResult> importCallBack = executorService.submit(importLcdMLotThread);
                        importCallBackList.add(importCallBack);
                    }
                }
            }

            int maxWaitCount = 1000;
            String resultMessage = StringUtils.EMPTY;
            for (Future<ImportMLotThreadResult> lcdImportCallBack : importCallBackList) {
                if (!StringUtils.isNullOrEmpty(resultMessage) || maxWaitCount <= 0) {
                    log.info("There has import error." + resultMessage);
                    break;
                }
                while (true) {
                    if (lcdImportCallBack.isDone()) {
                        ImportMLotThreadResult importResult = lcdImportCallBack.get();
                        if (!ResponseHeader.RESULT_SUCCESS.equals(importResult.getResult())) {
                            resultMessage = importResult.getResultMessage();
                        }
                        break;
                    } else {
                        Thread.sleep(200);
                        maxWaitCount--;
                        if (maxWaitCount == 0) {
                            resultMessage = MmsException.MM_MATERIAL_LOT_IMPORT_TIME_OUT;
                            break;
                        }
                    }
                }
            }
            if (!StringUtils.isNullOrEmpty(resultMessage)) {
                for (Future<ImportMLotThreadResult> importCallBack : importCallBackList) {
                    if (!importCallBack.isDone()) {
                        importCallBack.cancel(true);
                    }
                }
                log.info("----------------" + resultMessage);
                deleteImportMaterialLotUnit(importCode);
                if(CollectionUtils.isNotEmpty(parentLotIdList)){
                    packagedLotDetailRepository.deleteByPackagedLotIdIn(parentLotIdList);
                }
                importCode = StringUtils.EMPTY;
            }
            return importCode;
        } catch (Exception e){
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 物料批次分组处理
     * @param materialLotList
     * @param num
     * @return
     * @throws ClientException
     */
    private List<List<MaterialLot>> getMaterialLotGroupList(List<MaterialLot> materialLotList, int num) throws ClientException{
        try {
            int totalCount = materialLotList.size();
            int groupCount = ( totalCount / num ) + (totalCount % num == 0 ? 0 : 1);
            List<List<MaterialLot>> mLotList = new ArrayList<>(groupCount);
            for(int i = 0, from = 0, to = 0;  i < groupCount; i++){
                from = i*num;
                to = from + num;
                to = to > totalCount ? totalCount : to;
                List<MaterialLot> materialLotIds = materialLotList.subList(from, to);
                mLotList.add(materialLotIds);
            }
            return mLotList;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }
}
