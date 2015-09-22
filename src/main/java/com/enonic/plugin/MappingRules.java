package com.enonic.plugin;


import com.enonic.cms.api.client.model.content.Input;
import com.enonic.cms.api.client.model.content.SelectorInput;
import org.apache.commons.lang.ArrayUtils;
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
        }
        return false;
    }


    public static Input getInput(MappingObjectHolder mappingObjectHolder) {
        if ("HB-lenke".equals(mappingObjectHolder.getTargetContenttype().getName()) &&
                "lenke".equals(mappingObjectHolder.getSourceContenttype().getName()) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("src")) &&
                "language".equals(mappingObjectHolder.getInputMapping().getAttributeValue("dest"))) {
            return handleHBLinkLanguageTextToDropdownMapping(mappingObjectHolder);
        }
        return null;
    }

    private static Input handleHBLinkLanguageTextToDropdownMapping(MappingObjectHolder mappingObjectHolder) {
        HashMap<String,String> HBLinkDropdownMappings = new HashMap<>();
        HBLinkDropdownMappings.put("norwegian","norsk");
        HBLinkDropdownMappings.put("english","engelsk");
        HBLinkDropdownMappings.put("danish","dansk");
        HBLinkDropdownMappings.put("swedish","svensk");
        HBLinkDropdownMappings.put("spanish","spansk");
        HBLinkDropdownMappings.put("german", "tysk");
        HBLinkDropdownMappings.put("french","fransk");

        String destInput = mappingObjectHolder.getInputMapping().getAttributeValue("dest");
        SelectorInput input = new SelectorInput(destInput,null);

        String textValue = mappingObjectHolder.getContentInputElement().getValue();
        if (textValue==null){
            return input;
        }

        textValue = textValue.toLowerCase();

        List<String> legalDropdownValues = getLegalDropdownValues(mappingObjectHolder);
        for (String legalDropdownValue:legalDropdownValues){
            if (textValue.indexOf(HBLinkDropdownMappings.get(legalDropdownValue))!=-1){
                input = new SelectorInput(destInput, legalDropdownValue);
                break;
            }
        }
        return input;
    }

    private static List<String> getLegalDropdownValues(MappingObjectHolder mappingObjectHolder) {
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
