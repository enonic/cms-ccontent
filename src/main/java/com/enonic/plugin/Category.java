package com.enonic.plugin;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by rfo on 30/07/14.
 */
public class Category implements Serializable {
    public List<Category> categories = new ArrayList<Category>();

    private Integer key;
    private Integer superKey;
    private Integer contenttypeKey;
    private Integer contentCount;
    private Integer totalContentCount;
    private String title;
    private String contenttype;


    public Integer getKey() {
        return key;
    }

    public void setKey(Integer key) {
        this.key = key;
    }

    public Integer getContentCount() {
        return contentCount;
    }

    public void setContentCount(Integer contentCount) {
        this.contentCount = contentCount;
    }

    public Integer getTotalContentCount() {
        return totalContentCount;
    }

    public void setTotalContentCount(Integer totalContentCount) {
        this.totalContentCount = totalContentCount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContenttype() {
        return contenttype;
    }

    public void setContenttype(String contenttype) {
        this.contenttype = contenttype;
    }

    public Integer getContenttypeKey() {
        return contenttypeKey;
    }

    public void setContenttypeKey(Integer contenttypeKey) {
        this.contenttypeKey = contenttypeKey;
    }

    public Integer getSuperKey() {
        return superKey;
    }

    public void setSuperKey(Integer superKey) {
        this.superKey = superKey;
    }
}
