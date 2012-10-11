package org.scandroid;
/*
 *
 * Copyright (c) 2009-2012,
 *
 *  Galois, Inc. (Aaron Tomb <atomb@galois.com>)
 *  Steve Suh    <suhsteve@gmail.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. The names of the contributors may not be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.scandroid.util.CLISCanDroidOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import policy.GpsSmsSpec;
import policy.PolicyChecker;
import spec.AndroidSpecs;
import spec.ISpecs;
import synthMethod.MethodAnalysis;
import util.AndroidAnalysisContext;

import com.ibm.wala.dataflow.IFDS.TabulationResult;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;

import domain.CodeElement;
import domain.DomainElement;
import domain.IFDSTaintDomain;
import flow.FlowAnalysis;
import flow.InflowAnalysis;
import flow.OutflowAnalysis;
import flow.types.FlowType;

public class SeparateEntryAnalysis {
	private static final Logger logger = LoggerFactory.getLogger(SeparateEntryAnalysis.class);
	
    public static void main(String[] args) throws Exception {
        CLISCanDroidOptions options = new CLISCanDroidOptions(args, true);

        logger.info("Loading app.");
        AndroidAnalysisContext<IExplodedBasicBlock> analysisContext =
                new AndroidAnalysisContext<IExplodedBasicBlock>(options);
        if (analysisContext.entries == null || analysisContext.entries.size() == 0) {
            throw new IOException("No Entrypoints Detected!");
        }
        MethodAnalysis<IExplodedBasicBlock> methodAnalysis = null;
           //new MethodAnalysis<IExplodedBasicBlock>();
        for (Entrypoint entry : analysisContext.entries) {
            logger.info("Entry point: " + entry);
        }
        
        URI summariesURI = options.getSummariesURI();
        InputStream summaryStream = null;
        if ( null != summariesURI ) {
        	File summariesFile = new File(summariesURI);
        	
        	if ( !summariesFile.exists() ) {
        		logger.error("Could not find summaries file: "+summariesFile);
        		System.exit(1);
        	}
        	
        	summaryStream = new FileInputStream(summariesFile);
        }
        
        if(options.separateEntries()) {
            int i = 1;
            for (Entrypoint entry : analysisContext.entries) {
                logger.info("** Processing entry point " + i + "/" +
                        analysisContext.entries.size() + ": " + entry);
                LinkedList<Entrypoint> localEntries = new LinkedList<Entrypoint>();
                localEntries.add(entry);
                analyze(analysisContext, localEntries, methodAnalysis, summaryStream, null);
                i++;
            }
        } else {
            analyze(analysisContext, analysisContext.entries, methodAnalysis, summaryStream, null);
        }
    }

    /**
     * @param analysisContext
     * @param localEntries
     * @param methodAnalysis
     * @param monitor 
     * @return the number of permission outflows detected
     */
    public static int 
        analyze(AndroidAnalysisContext<IExplodedBasicBlock> analysisContext,
                 LinkedList<Entrypoint> localEntries, 
                 MethodAnalysis<IExplodedBasicBlock> methodAnalysis,
                 InputStream summariesStream, IProgressMonitor monitor) {
        try {
            analysisContext.buildGraphs(localEntries, summariesStream);

            logger.info("Supergraph size = "
                    + analysisContext.graph.getNumberOfNodes());

            Map<InstanceKey, String> prefixes;
            if(analysisContext.getOptions().stringPrefixAnalysis()) {
                logger.info("Running prefix analysis.");
                prefixes = UriPrefixAnalysis.runAnalysisHelper(analysisContext.cg, analysisContext.pa);
                logger.info("Number of prefixes = " + prefixes.values().size());
            } else {
                prefixes = new HashMap<InstanceKey, String>();
            }
            
            
            ISpecs specs = new AndroidSpecs();
             
            logger.info("Running inflow analysis.");
            Map<BasicBlockInContext<IExplodedBasicBlock>, 
                Map<FlowType<IExplodedBasicBlock>, Set<CodeElement>>> initialTaints = 
                  InflowAnalysis.analyze(analysisContext, prefixes, specs);
            
            logger.info("  Initial taint size = "
                    + initialTaints.size());
                       
            logger.info("Running flow analysis.");
            IFDSTaintDomain<IExplodedBasicBlock> domain = new IFDSTaintDomain<IExplodedBasicBlock>();
            TabulationResult<BasicBlockInContext<IExplodedBasicBlock>, CGNode, DomainElement> 
              flowResult = FlowAnalysis.analyze(analysisContext, initialTaints, domain, methodAnalysis, monitor);

            logger.info("Running outflow analysis.");
            Map<FlowType<IExplodedBasicBlock>, Set<FlowType<IExplodedBasicBlock>>> permissionOutflow = OutflowAnalysis
                    .analyze(analysisContext, flowResult, domain, specs);
            logger.info("  Permission outflow size = "
                    + permissionOutflow.size());
            
            //logger.info("Running Checker.");
    		//Checker.check(permissionOutflow, perms, prefixes);

            
            logger.info("");
            logger.info("================================================================");
            logger.info("");

            for (Map.Entry<BasicBlockInContext<IExplodedBasicBlock>, Map<FlowType<IExplodedBasicBlock>, Set<CodeElement>>> e : initialTaints
                    .entrySet()) {
                logger.info(e.getKey().toString());
                for (Map.Entry<FlowType<IExplodedBasicBlock>, Set<CodeElement>> e2 : e.getValue()
                        .entrySet()) {
                    logger.info(e2.getKey() + " <- " + e2.getValue());
                }
            }
            for (Map.Entry<FlowType<IExplodedBasicBlock>, Set<FlowType<IExplodedBasicBlock>>> e : permissionOutflow
                    .entrySet()) {
                logger.info(e.getKey().toString());
                for (FlowType t : e.getValue()) {
                    logger.info("    --> " + t);
                }
            }

            if (analysisContext.getOptions().useDefaultPolicy()) {
                PolicyChecker checker = new PolicyChecker(new GpsSmsSpec());
                boolean good = checker.flowsSatisfyPolicy(permissionOutflow);
                if (good) {
                    logger.info("Passed policy check. No flows from GPS to SMS.");
                } else {
                    logger.error("Failed policy check! Flows exist from GPS to SMS.");
                }
            }
            
//            System.out.println("DOMAIN ELEMENTS");
//            for (int i = 1; i < domain.getSize(); i++) {
//            	System.out.println("#"+i+" - "+domain.getMappedObject(i));
//            }
//            System.out.println("------");
//            for (CGNode n:loader.cg.getEntrypointNodes()) {
//            	for (int i = 0; i < 6; i++)
//            	{
//            		try {
//            		System.out.println(i+": ");
//            		String[] s = n.getIR().getLocalNames(n.getIR().getInstructions().length-1, i);
//            		
//            		for (String ss:s)
//            			System.out.println("\t"+ss);
//            		}
//            		catch (Exception e) {
//            			System.out.println("exception at " + i);
//            		}
//            	}
//            }
//            
//            System.out.println("------");
//            for (CGNode n:loader.cg.getEntrypointNodes()) {
//            	for (SSAInstruction ssa: n.getIR().getInstructions()) {
////            		System.out.println("Definition " + ssa.getDef() + ":"+ssa);
//            		System.out.println("Definition "+ssa);
//            	}
//            }
            return permissionOutflow.size();
        } catch (com.ibm.wala.util.debug.UnimplementedError e) {
            logger.error("exception during analysis", e);
        } catch (CancelException e){
            logger.warn("Canceled", e);
        }
        return 0;
    }
}
