package com.admin.service;

import com.admin.common.dto.RedeemCodeDto;
import com.admin.common.lang.R;
import com.admin.entity.RedeemCode;

public interface RedeemService {
    R listCodes();
    R createCode(RedeemCode code);
    R updateCode(RedeemCode code);
    R deleteCode(Long id);
    R useRedeemCode(RedeemCodeDto dto);
}
