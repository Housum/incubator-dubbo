package org.apache.dubbo.demo;

import java.io.Serializable;
import java.util.List;

/**
 * @author qibao
 * @since 2019-08-02
 */

public class ByteObjects implements Serializable {

    private List<byte[]> byteList;

    public void setByteList(List<byte[]> byteList) {
        this.byteList = byteList;
    }

    public List<byte[]> getByteList() {
        return byteList;
    }
}
