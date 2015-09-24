package com.enonic.plugin;

public class Contenttype implements Comparable<Contenttype>{
    private Integer key;
    private String name;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Contenttype that = (Contenttype) o;

        if (key != null ? !key.equals(that.key) : that.key != null) return false;
        if (name != null ? !name.equals(that.name) : that.name != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = key != null ? key.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        return result;
    }

    public Contenttype(){


    }

    public Contenttype(Integer key, String name){
        this.key = key;
        this.name = name;
    }

    public Integer getKey() {
        return key;
    }

    public void setKey(Integer key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int compareTo(Contenttype o) {
        if (getKey()==o.getKey()){
            return 0;
        }else if (getKey()<o.getKey()){
            return -1;
        }else{
            return 1;
        }
    }

    /*@Override
    public boolean equals(Object object){
        Contenttype contenttype = (Contenttype) object;
        if (contenttype.getKey()!=null && this.getKey()!=null && this.getKey().equals(contenttype.getKey())){
            return true;
        }else if (contenttype.getName()!=null && this.getName()!=null && this.getName().equals(contenttype.getName())){
            return true;
        }
        else{
            return false;
        }
    }
    @Override
    public int hashCode(){
        return -123456789;
    }*/


}
