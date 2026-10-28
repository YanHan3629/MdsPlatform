package com.fwdrobo.sirius.util;
import java.util.Locale;

/** Dataset descriptions stay outside business snapshots; other artifact repos keep their README support. */
public final class DataSpaceBusinessFilePolicy {
    private DataSpaceBusinessFilePolicy(){}
    public static boolean isDescription(String path){
        String name=path.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        return name.equals("数据集说明.md")||name.equals("readme")||name.matches("readme\\.[a-z0-9]+");
    }
}
