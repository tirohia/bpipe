/*
 * Copyright (c) 2012 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe

import java.util.logging.Level;
import java.util.logging.Logger
import java.util.regex.Pattern

import bpipe.storage.LocalFileSystemStorageLayer
import bpipe.storage.LocalPipelineFile

import groovy.transform.CompileStatic
import groovy.util.logging.Log;

/**
 * Represents a "magic" input object that automatically 
 * understands property references as file extensions. 
 * All "input" variables that are implicitly passed to 
 * Bpipe stages are actually PipelineInput objects.  Apart from their 
 * special "magic" properties they look and behave just like String objects
 * because they dynamically defer missing method invocations to 
 * the wrapped string objec that they contain. 
 * 
 * @author simon.sadedin@mcri.edu.au
 */
class PipelineInput {
    
    private static Logger log = Logger.getLogger('PipelineInput')
    
    /**
     * Support implicit cast to String when creating File objects
     */
    static {
        File.metaClass.constructor << {  PipelineInput i -> new File(i.toString()) }
    }
    
    /**
     * Raw inputs
     */
    List<PipelineFile> input
    
    /**
     * The default value is returned when toString() is called.
     * The default default-value is the first input ("input1"),
     * but that is overridden in certain cases (eg: "input2.txt")
     */
    int defaultValueIndex = 0
    
    /**
     * In some cases an input spawns and returns a new input.
     * In that case, the child needs to be able to reflect
     * resolved inputs up to the parent
     */
    PipelineInput parent
    
    /**
     * List of inputs actually resolved by interception of $input.x 
     * style property references
     */
    List<PipelineFile> resolvedInputs = []
    
    List<PipelineStage> stages 
    
    /**
     * A prefix to be applied to all attempts to resolve properties by this pipeline input.
     * This is applied to child PipelineInput objects when multiple input extensions are 
     * used: $input.foo.bar.csv
     */
    String extensionPrefix
    
    /**
     * Error suggested by parent PipelineInput to be thrown if resolution fails for
     * a property on this PipelineInput object. Used in the case of multiple input extensions.
     */
    InputMissingError parentError
    
    /**
     * If a filter is in operation, the current list of file extensions
     * that it is allowing. This list is shared with PipelineOutput
     * and may be modified if new input extensions are discovered.
     */
    List<String> currentFilter = []
    
    /**
     * Set of aliases to use in mapping file names
     */
    Aliases aliases

    PipelineInput(List<PipelineFile> input, List<PipelineStage> stages, Aliases aliases) {
        this.stages = stages;
        this.input = input
        this.aliases = aliases
        
        if(!input.every { it instanceof PipelineFile })
            throw new Exception("bad input")
    }
    
    @CompileStatic
    PipelineFile getResolvedValue() {
        if(parentError)
            throw parentError
            
        Collection boxed = Utils.box(input)
        if(defaultValueIndex>=boxed.size())
           throw new PipelineError("Expected ${defaultValueIndex+1} or more inputs but fewer provided")
            
        PipelineFile resolvedValue = boxed[defaultValueIndex]
        if(!this.resolvedInputs.any { it.path == resolvedValue.path })
            this.resolvedInputs.add(resolvedValue)
            
        return resolvedValue
    }
    
    String toString() {
        PipelineFile resolvedValue = getResolvedValue()
        PipelineFile resolvedFile =  this.aliases[resolvedValue]
        return resolvedFile.renderToCommand()
    }
    
    void addResolvedInputs(List<PipelineFile> objs) {
        
        assert objs.every { it instanceof PipelineFile }
        
        for(inp in objs) {
            if(!this.resolvedInputs.any { it.path == inp.path })
                this.resolvedInputs.add(inp)
        }
        
        if(parent)
            parent.addResolvedInputs(objs)
            
        addFilterExts(objs)
    }
	
	String getPrefix() {
        return PipelineCategory.getPrefix(this.toString());
	}
    
    /**
     * Support accessing inputs by index - allows the user to use the form
     *   exec "cp ${input[0]} $output"
     */
    String getAt(int i) {
        def inputs = this.input
        if(inputs.size() <= i)
            throw new PipelineError("Insufficient inputs:  at least ${i+1} inputs are expected but only ${inputs.size()} are available")
        this.addResolvedInputs([inputs[i]])
        return inputs[i]
    }
    
    /**
     * Search for the most recent input or output of any stage
     * that has the given file extension
     */
    def propertyMissing(String name) {
		log.info "Searching for missing Property: $name"
        List<PipelineFile> resolved
        InputMissingError ime
        try {
            
            if(name =="dir") {
                log.info "Trying to resolve input as directory ...."
                resolved = resolveInputAsDirectory()
                if(!resolved) 
                    throw new PipelineError("Expected a directory as input, but no current input to this stage was a directory: \n" + Utils.box(input).join("\n"))
            }
            else {
              def exts = this.extensionPrefix?[extensionPrefix+"."+name]:[name]
              resolved = resolveInputsEndingWith(exts)
              if(resolved.size() <= defaultValueIndex)
                  throw new PipelineError("Insufficient inputs: at least ${defaultValueIndex+1} inputs are expected with extension .${name} but only ${resolved.size()} are available")
            }
            parentError=null
        		mapToCommandValue(resolved)
        }
        catch(InputMissingError e) {
            log.info("No input resolved for property $name: returning child PipelineInput for possible double extension resolution")
            resolved = this.resolvedInputs
            ime = e
        }
        
        PipelineInput childInp = new PipelineInput(resolved.clone(), stages, aliases)
        childInp.parent = this
        childInp.resolvedInputs = resolved.clone()
        childInp.currentFilter = this.currentFilter
        childInp.extensionPrefix = this.extensionPrefix ? this.extensionPrefix+"."+name : name
        childInp.defaultValueIndex = defaultValueIndex
        childInp.parentError = ime
        return childInp;
        
     }
    
    @CompileStatic
    List<PipelineFile> resolveInputAsDirectory() {
        List<List<PipelineFile>> outputStack = this.computeOutputStack()
        for(List<PipelineFile> outputs in outputStack) {
            def result = outputs.find { PipelineFile f -> f.isDirectory() } 
            if(result)
                return [result]
        }
    }
	
	/**
	 * Maps given values to a form ready to be included
	 * in an executing command.
	 * <p>
	 * In this case, maps to the first value resolved in the 
	 * given values.  See also {@link MultiPipelineInput#mapToCommandValue(Object)}
	 */
	String mapToCommandValue(def values) {
        
        PipelineFile rawResolvedInput = Utils.box(values)[defaultValueIndex]
        
        assert rawResolvedInput instanceof PipelineFile
        
        def result = this.aliases[rawResolvedInput]
        log.info "Adding resolved input $result (raw input = $rawResolvedInput)"
        this.addResolvedInputs([rawResolvedInput])
        return result
	}
    
    /**
     * Here we implement pseudo inheritance from the String class.
     * The idea is that people can use this object more or less like
     * a String object.
     */
    def methodMissing(String name, args) {
        // faux inheritance from String class
        if(name in String.metaClass.methods*.name) {
            return String.metaClass.invokeMethod(this.toString(), name, args?:[])
        }
        else {
            throw new MissingMethodException(name, PipelineInput, args)
        }
    }
    
    def plus(String str) {
        return this.toString() + str 
    }
    
    /**
     * There seems to be an implementation of split() in defaultGroovyMethods,
     * but it does NOT do the right thing like a String would.
     * @return
     */
    def split() {
        toString().split()
    }
        
    /**
     * Search backwards through the inputs to the current stage and the outputs of
     * previous stages to find the first output that ends with the extension specified
     * for each of the given exts.
     */
    List<PipelineFile> resolveInputsEndingWith(def exts) {    
        resolveInputsEndingWithPatterns(exts.collect { it.replace('.','\\.')+'$' }, exts)
    }
    
    List<PipelineFile> probe(def pattern) {
        if(pattern instanceof String)
            pattern = pattern.replace('.','\\.')+'$' 
        
        // TODO: refactor the resolveInputsEndingWithPatterns method to not return a
        // list with [null] when there is no result
        List<PipelineFile> result = resolveInputsEndingWithPatterns([pattern], [pattern], false)
        if(result.size()==1 && result[0]  == null)
            return []
        return result
    }
    
    /**
     * Search the pipeline hierarchy backwards to find inputs matching the patterns specified 
     * in 'exts'.
     * <p>
     * Note: exts can be actual java.util.regex.Regex objects. However they are converted to
     * Strings and parsed and manipulated, so take care in using them this way.
     * 
     * @param exts  pattern (regular expressions) to match
     * @param origs user friendly versions of the above to display in errors
     * @return  list of inputs matching given patterns
     */
    List<PipelineFile> resolveInputsEndingWithPatterns(def exts, def origs, failIfNotFound=true) {    
        
        def orig = exts
        synchronized(stages) {
            
            List<List<PipelineFile>> reverseOutputs = computeOutputStack()
	        
            List missingExts = []
	        def filesWithExts = [Utils.box(exts),origs].transpose().collect { extsAndOrigs ->
	            String pattern = extsAndOrigs[0]
	            String origName = extsAndOrigs[1]
                
	            List<String> resolved = resolveInputFromExtension(pattern, origName, reverseOutputs)
                if(resolved == null)
                    missingExts << origName
                return resolved
	        }
            
	        if(missingExts && failIfNotFound)
	            throw new InputMissingError("Unable to locate one or more specified inputs from pipeline with the following extension(s):\n\n" + missingExts*.padLeft(15," ").join("\n"))
	            
			log.info "Found files with exts $exts : $filesWithExts"
	        return filesWithExts.flatten().unique()
        }
    }
    
    
    @CompileStatic
    List<PipelineFile> resolveInputFromExtension(String regex, String origName, List<List<PipelineFile>> reverseOutputs) {
        
            Pattern wholeMatch = ~('(^|^.*/)' + regex + '$')
                
            // Special case: treat a leading dot as a literal dot.
            // ie: if the user specifies ".xml", they probably mean
            // literally ".xml" and not "any character" + "xml"
            if(regex.startsWith("."))
                regex = "\\." + regex.substring(1)
                    
            if(!regex.startsWith("\\.") )
                regex = "\\." + regex
                    
            regex = '^.*' + regex
            
            Pattern regexPattern = ~regex
            
            log.info "Resolving inputs matching pattern $regex"
            for(s in reverseOutputs) {
                if(log.isLoggable(Level.INFO))
	                log.info("Checking outputs ${s}")
                        
                PipelineFile o = s.find { PipelineFile p -> p?.toString()?.matches(wholeMatch) }
                if(o)
                    return s.grep { PipelineFile p -> p?.toString()?.matches(wholeMatch) }
                        
                if(!o) {
	                o = s.find { PipelineFile p -> p?.matches(regexPattern) }
	                if(o)
	                    return s.grep { PipelineFile p -> p?.toString()?.matches(regexPattern) }
                }
            }
            return null
    }
    
    /**
     * Compute a list of outputs from previous stages, in reverse order that they occurred
     * in the pipeline, and includes the original inputs as the last stage. This "stack" of inputs
     * provides an appropriate order for searching for inputs to a pipeline stage.
     */
// Causes ClassCastException at line ~395
//    @CompileStatic
    List<List<PipelineFile>> computeOutputStack() {
        
        List relatedThreads = [Thread.currentThread().id, Pipeline.rootThreadId]
        
        Pipeline pipeline = Pipeline.currentRuntimePipeline.get()
        while(pipeline.parent && pipeline.parent!=pipeline) {
            relatedThreads.add(pipeline.parent.threadId)
            pipeline = pipeline.parent
        }
        
        PipelineStage currentStage = stages[-1]
        
        List<PipelineStage> reverseStages = stages.reverse().grep {  PipelineStage stage ->
            // Only consider outputs from threads that are related to us but don't consider our own
            // (yet to be created) outputs
            
            !stage.is(currentStage) && stage.context.threadId in relatedThreads && !inputBelongsToStage(stage)
            
            // !this.is(it.context.@inputWrapper) && ( this.parent == null || !this.parent.is(it.context.@inputWrapper)    )
        }
        
        List reverseOutputs = new ArrayList(reverseStages.size()*2)
        for(PipelineStage stage in reverseStages) {
            
            if(stage.context.nextInputs != null) {
                log.info "nextInputs are $stage.context.nextInputs" 
                List nextInputs = Utils.box(stage.context.nextInputs) as List
                reverseOutputs.add(nextInputs)
            }
            
            List outputs = Utils.box(stage.context.@output)  as List
            if(!outputs.isEmpty()) {
                log.info "Outputs in search from $stage.stageName $outputs"            
                reverseOutputs.add(outputs)
            }
        }
        
        // Add a final stage that represents the original inputs (bit of a hack)
        // You can think of it as the initial inputs being the output of some previous stage
        // that we know nothing about
        List previousInputs = LocalPipelineFile.from(Utils.box(stages[0].context.@input) as List)
        log.info "Supplementing with outputs from previous inputs: " + previousInputs
        reverseOutputs.add(previousInputs)
            
        // Consider not just the actual inputs to the stage, but also the *original* unmodified inputs
        if(stages[0].originalInputs) {
            List originalInputs = LocalPipelineFile.from(Utils.box(stages[0].originalInputs) as List)
            log.info "Supplementing with original outputs: " + originalInputs
  	        reverseOutputs.add(originalInputs)
        }
        
        // Add an initial stage that represents the current input to this stage.  This way
        // if the from() spec is used and matches the actual inputs then it will go with those
        // rather than searching backwards for a previous match
        log.info "Finally, add my actual input as the first thing to check: " + this.input
        reverseOutputs.add(0,this.@input)
            
        return reverseOutputs
    }
    
    /**
     * Return true if this input wrapper or one of its parents is set as
     * the PipelineInput for the given stage
     * 
     * @param stage
     */
    @CompileStatic
    boolean inputBelongsToStage(PipelineStage stage) {
        PipelineInput stageInputWrapper = stage.context.@inputWrapper
        PipelineInput pinp = this
        while(pinp != null) {
            if(pinp.is(stageInputWrapper))
                return true
            pinp = pinp.parent
        }
    }
    
    void addFilterExts(List<PipelineFile> files) {
        // If a filter is in operation and the file extension of the input was not already
        // resolved by the filter, add it here since this input could now be the input targeted
        // for filtering (the user may specify it using an $output.<ext> reference
        if(currentFilter) { 
          files.collect { it.path.substring(it.path.lastIndexOf('.')+1) }.each {
              if(!currentFilter.contains(it)) {
                  currentFilter.add(it)
              }
          }
        }
    }
    
    public int size() {
        if(this.@input)
          Utils.box(this.@input)[0].size()
        else
            0
    }
    
}
