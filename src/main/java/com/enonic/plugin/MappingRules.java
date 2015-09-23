package com.enonic.plugin;


import com.enonic.cms.api.client.model.content.Input;
import com.enonic.cms.api.client.model.content.SelectorInput;
import org.jdom.Element;
import org.jdom.xpath.XPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MappingRules {
    Logger LOG = LoggerFactory.getLogger(MappingRules.class);


    public static boolean hasSpecialHandling(MappingObjectHolder mappingObjectHolder) {

        if ("HB-lenke".equals(mappingObjectHolder.getTargetContenttype().getName()) &&
                "lenke".equals(mappingObjectHolder.getSourceContenttype().getName()) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("src")) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("dest"))) {
            return true;
        }else if ("HB-artikkel".equals(mappingObjectHolder.getTargetContenttype().getName()) &&
                "artikkel".equals(mappingObjectHolder.getSourceContenttype().getName()) &&
                "size".equals(mappingObjectHolder.getInputMapping().getAttributeValue("src")) &&
                "image-size".equals(mappingObjectHolder.getInputMapping().getAttributeValue("dest"))) {
            return true;
        }
        return false;
    }


    public static Input getInput(MappingObjectHolder mappingObjectHolder) {
        if ("HB-lenke".equals(mappingObjectHolder.getTargetContenttype().getName()) &&
                "lenke".equals(mappingObjectHolder.getSourceContenttype().getName()) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("src")) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("dest"))) {
            return handleHBLinkLanguageTextToDropdownMapping(mappingObjectHolder);
        }else if ("HB-artikkel".equals(mappingObjectHolder.getTargetContenttype().getName()) &&
                "artikkel".equals(mappingObjectHolder.getSourceContenttype().getName()) &&
                "size".equals(mappingObjectHolder.getInputMapping().getAttributeValue("src")) &&
                "image-size".equals(mappingObjectHolder.getInputMapping().getAttributeValue("dest"))) {
            return handleHBArticleListImageDropdownMapping(mappingObjectHolder);
        }
        return null;
    }

    private static Input handleHBArticleListImageDropdownMapping(MappingObjectHolder mappingObjectHolder) {
        HashMap<String,String> dropdownMappings = new HashMap<String,String>();
        dropdownMappings.put("full","287");
        dropdownMappings.put("medium","210");
        dropdownMappings.put("small","140");

        String destInput = mappingObjectHolder.getInputMapping().getAttributeValue("dest");
        SelectorInput input = new SelectorInput(destInput,null);

        String textValue = mappingObjectHolder.getContentInputElement().getValue();
        if (textValue==null){
            return input;
        }

        List<String> legalDropdownValues = getLegalSelectorValues(mappingObjectHolder);
        for (String legalDropdownValue:legalDropdownValues){
            if (textValue.indexOf(dropdownMappings.get(legalDropdownValue))!=-1){
                input = new SelectorInput(destInput, legalDropdownValue);
                break;
            }
        }

        return input;
    }

    private static Input handleHBLinkLanguageTextToDropdownMapping(MappingObjectHolder mappingObjectHolder) {
        HashMap<String,String> dropdownMappings = new HashMap<String,String>();
        dropdownMappings.put("norwegian","norsk");
        dropdownMappings.put("english","engelsk");
        dropdownMappings.put("danish","dansk");
        dropdownMappings.put("swedish","svensk");
        dropdownMappings.put("spanish","spansk");
        dropdownMappings.put("german", "tysk");
        dropdownMappings.put("french","fransk");

        String destInput = mappingObjectHolder.getInputMapping().getAttributeValue("dest");
        SelectorInput input = new SelectorInput(destInput,null);

        String textValue = mappingObjectHolder.getContentInputElement().getValue();
        if (textValue==null){
            return input;
        }

        textValue = textValue.toLowerCase();

        List<String> legalDropdownValues = getLegalSelectorValues(mappingObjectHolder);
        for (String legalDropdownValue:legalDropdownValues){
            if (textValue.indexOf(dropdownMappings.get(legalDropdownValue))!=-1){
                input = new SelectorInput(destInput, legalDropdownValue);
                break;
            }
        }
        return input;
    }

    private static List<String> getLegalSelectorValues(MappingObjectHolder mappingObjectHolder) {
        List<String> legalDropdownValues = new ArrayList<String>();
        String destInput = mappingObjectHolder.getInputMapping().getAttributeValue("dest");
        try {
            List<Element> options = XPath.selectNodes(mappingObjectHolder.getTargetInputElement(), "options/option");
            Iterator<Element> optionsIt = options.iterator();
            while (optionsIt.hasNext()){
                String legalValue = optionsIt.next().getAttributeValue("value");
                if (legalValue!=null){
                    legalDropdownValues.add(legalValue);
                }
            }
        }catch (Exception e){
        }

        return legalDropdownValues;
    }
}
