package com.newbiest.gc.rest.box.print.parameter;

import com.newbiest.gc.model.StockOutCheck;
import com.newbiest.mms.model.MaterialLot;
import com.newbiest.msg.RequestBody;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;


@Data
@ApiModel("具体请求操作信息")
public class GcGetBboxPrintParaRequestBody extends RequestBody {
	
	private static final long serialVersionUID = 1L;

	private Long materialLotRrn;

	private String printCount;

}
