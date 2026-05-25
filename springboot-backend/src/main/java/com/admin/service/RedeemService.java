package com.admin.service;

import com.admin.common.dto.RedeemCodeDto;
import com.admin.common.lang.R;

public interface RedeemService {
    R useRedeemCode(RedeemCodeDto dto);
}
