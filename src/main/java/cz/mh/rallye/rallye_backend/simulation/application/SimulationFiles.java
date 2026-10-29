package cz.mh.rallye.rallye_backend.simulation.application;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;import cz.mh.rallye.rallye_backend.templates.application.TemplateImageFiles;
@Component public class SimulationFiles {public final TemplateImageFiles storage;public SimulationFiles(@Value("${rallye.simulation.file-directory:${user.home}/.rallye/simulation-files}")String root){storage=new TemplateImageFiles(root,"race-simulations");}}
