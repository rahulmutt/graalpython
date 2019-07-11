/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.oracle.graal.python.test.parser;

import java.io.File;
import org.junit.Test;

/**
 *
 * @author petr
 */
public class RuntimeFileTests extends ParserTestBase {
    
    @Test
    public void builtins() throws Exception {
        checkScopeAndTree();
    }
    
    private void checkScopeAndTree()  throws Exception{
        File testFile = getTestFileFromTestAndTestMethod();
        checkScopeFromFile(testFile, true);
        checkTreeFromFile(testFile, true);
    }
}
