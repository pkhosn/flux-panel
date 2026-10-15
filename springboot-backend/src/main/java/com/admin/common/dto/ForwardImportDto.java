package com.admin.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class ForwardImportDto {
    private List<ForwardDto> rules;
}
