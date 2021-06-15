package com.newbiest.gc.rest.rawMlot.print.parameter;

import com.newbiest.mms.model.MaterialLot;
import com.newbiest.msg.RequestBody;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;


@Data
@ApiModel("具体请求操作信息")
public class GcGetRawMlotPrintParaRequestBody extends RequestBody {
	
	private static final long serialVersionUID = 1L;

	private List<MaterialLot> materialLots;

	private String printCount;

	@ApiModelProperty(value = "操作类型")
	private String actionType;
}
