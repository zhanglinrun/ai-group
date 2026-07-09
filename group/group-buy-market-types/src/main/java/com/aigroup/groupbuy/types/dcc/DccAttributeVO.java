package com.aigroup.groupbuy.types.dcc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DccAttributeVO implements Serializable {
    private String key;
    private String value;
}
