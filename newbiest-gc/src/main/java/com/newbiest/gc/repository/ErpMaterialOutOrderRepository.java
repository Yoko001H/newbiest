package com.newbiest.gc.repository;

import com.newbiest.base.exception.ClientException;
import com.newbiest.base.repository.custom.IRepository;
import com.newbiest.gc.model.ErpMaterialOutOrder;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ErpMaterialOutOrderRepository extends IRepository<ErpMaterialOutOrder, Long> {

    List<ErpMaterialOutOrder> findByTypeAndSynStatusNotIn(String type, List<String> asyncStatus);

    @Query("update ErpMaterialOutOrder p set p.synStatus=:synStatus, p.errorMemo = :errorMemo, p.userId = :userId where p.seq in (:seqList)")
    @Modifying
    void updateSynStatusAndErrorMemoAndUserIdBySeq(@Param("synStatus") String synStatus,@Param("errorMemo")  String errorMemo,@Param("userId") String userId,@Param("seqList")  List<Long> seqList) throws ClientException;

}
