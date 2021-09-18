package com.newbiest.mms.service;

import com.newbiest.base.exception.ClientException;
import com.newbiest.mms.model.MaterialLot;
import com.newbiest.mms.model.MaterialLotUnit;

import java.util.List;
import java.util.Map;

/**
 * @author guoxunbo
 * @date 4/6/21 3:04 PM
 */
public interface PrintService {

    void printWaferCstAndLotLabel(List<MaterialLot> materialLotList) throws ClientException;
    void printReceiveWltCpLotLabel(List<MaterialLot> materialLotList, String printCount) throws ClientException;
    void printWltOrCpLabel(MaterialLot materialLot, String printCount) throws ClientException;
    void printWltBoxLabel(MaterialLot materialLot, String printCount) throws ClientException;
    void printWltBboxLabel(List<MaterialLotUnit> materialLotUnitList) throws ClientException;

    void printRmaMaterialLotLabel(List<MaterialLot> materialLotList) throws ClientException;

    void printMaterialLotObliqueBoxLabel(List<MaterialLot> materialLotList, String expressNumber) throws ClientException;
    void printRwLotCstLabel(List<MaterialLot> materialLotList, String printCount) throws ClientException;
    void rePrintRwLotCstLabel(MaterialLot materialLot, String printCount) throws ClientException;
    void printRwCstLabel(MaterialLot materialLot, String printCount) throws ClientException;
    void printRwStockOutLabel(MaterialLot materialLot) throws ClientException;
    void printRwLotIssueLabel(List<MaterialLot> materialLotList, String printCount) throws ClientException;

    void printCobBoxLabel(MaterialLot materialLot,  String printCount) throws ClientException;
    void printCobBBoxLabel(MaterialLot materialLot) throws ClientException;
    void printBoxQRCodeLabel(MaterialLot materialLot, String printVboxLabelFlag) throws ClientException;

    void printComBoxAndCustomerLabel(MaterialLot materialLot, String subcode, String printCount) throws ClientException;
    void rePrintVBxoLabel(List<Map<String, Object>> parameterMapList) throws ClientException;

    void printRawMlotIRLabel(List<MaterialLot> materialLots) throws ClientException;
    void printRawMlotGlueLabel(List<MaterialLot> materialLots) throws ClientException;
    void printIRABoxLabel(List<MaterialLot> materialLots) throws ClientException;
}
